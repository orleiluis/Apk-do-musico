"""
Converte o modelo htdemucs_6s (Demucs v4, Meta, licença MIT) para ONNX, no formato que o app usa,
e confere o resultado contra o PyTorch. Roda no GitHub Actions (precisa de internet para baixar o modelo).

O ONNX não leva a STFT/iSTFT (o exportador do PyTorch não lida com números complexos): o app calcula
a STFT em Java, o modelo recebe a forma de onda e o espectrograma e devolve os dois ramos, e o app
faz a iSTFT e soma. É a mesma conta que o HTDemucs.forward faz por dentro.

Saídas em <out>/:
  model/htdemucs_6s.onnx      grafo, com os pesos apontando para htdemucs_6s.onnx.data
  model/htdemucs_6s.half      os pesos em float16 (o app expande para float32 na primeira vez)
  model/htdemucs_6s.json      fontes, taxa, tamanhos
  testdata/                   áudio de teste e o resultado do PyTorch, para os testes em Java
"""
import json
import math
import os
import sys
import time

import numpy as np
import onnx
import onnxruntime as ort
import torch
from onnx import numpy_helper

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from split import run_parts, split  # noqa: E402

from demucs.apply import apply_model
from demucs.pretrained import get_model

OUT = sys.argv[1] if len(sys.argv) > 1 else "out"
NAME = "htdemucs_6s"
DATA = NAME + ".onnx.data"
os.makedirs(os.path.join(OUT, "model"), exist_ok=True)
os.makedirs(os.path.join(OUT, "testdata"), exist_ok=True)
os.makedirs(os.path.join(OUT, "work"), exist_ok=True)

torch.manual_seed(0)
torch.set_num_threads(os.cpu_count() or 2)

bag = get_model(NAME)
bag.eval()
model = bag.models[0]
model.eval()
assert model.cac, "o app espera o modo complex-as-channels"
sources = list(model.sources)
SR = model.samplerate
SEG = int(model.segment * SR)  # segment é Fraction: a conta fica exata, como no apply_model
NFFT, HOP = model.nfft, model.hop_length
FRAMES = int(math.ceil(SEG / HOP))
BINS = NFFT // 2
S = len(sources)
print(f"fontes={sources} taxa={SR} trecho={SEG} nfft={NFFT} hop={HOP} quadros={FRAMES}")
print("parâmetros:", sum(p.numel() for p in model.parameters()))

ORIG = {k: getattr(model, k) for k in ("_spec", "_magnitude", "_mask", "_ispec")}


class Core(torch.nn.Module):
    """HTDemucs sem a STFT: entra forma de onda + espectrograma (cac), sai o ramo de frequência e o do tempo."""

    def __init__(self, m):
        super().__init__()
        self.m = m

    def forward(self, mix, mag):
        m = self.m
        box = {}

        def mask(z, x):
            box["freq"] = x
            return x

        m._spec = lambda x: mag
        m._magnitude = lambda z: z
        m._mask = mask
        # o ramo de frequência volta para o app; aqui ele vale zero na soma final
        m._ispec = lambda z, length=None, scale=0: torch.zeros((), dtype=mix.dtype)
        try:
            t = m(mix)
        finally:
            for k in ORIG:
                m.__dict__.pop(k, None)
        return box["freq"], t


core = Core(model).eval()


def snr(ref, est):
    ref = np.asarray(ref, np.float64)
    err = np.asarray(est, np.float64) - ref
    return 10 * math.log10((np.sum(ref ** 2) + 1e-20) / (np.sum(err ** 2) + 1e-20))


# ------------------------------------------------------------------ áudio de teste (sintético, determinístico)
def test_signal(seconds=12.0):
    n = int(seconds * SR)
    t = np.arange(n) / SR
    rng = np.random.default_rng(1234)
    left = np.zeros(n)
    right = np.zeros(n)
    chords = [[220.0, 277.18, 329.63], [196.0, 246.94, 293.66], [174.61, 220.0, 261.63], [164.81, 207.65, 246.94]]
    beat = 0.5
    for i, start in enumerate(np.arange(0, seconds, 2.0)):
        a, b = int(start * SR), min(n, int((start + 2.0) * SR))
        tt = t[a:b] - start
        env = np.minimum(1, tt * 20) * np.exp(-tt * 0.6)
        for k, f in enumerate(chords[i % 4]):
            tone = sum(np.sin(2 * np.pi * f * h * tt) / h for h in range(1, 7))  # timbre "guitarra"
            left[a:b] += 0.12 * env * tone * (0.8 if k == 0 else 0.5)
            right[a:b] += 0.12 * env * tone * (0.5 if k == 0 else 0.8)
        bass = np.sin(2 * np.pi * chords[i % 4][0] / 2 * tt) * np.exp(-tt * 0.3)
        left[a:b] += 0.25 * bass
        right[a:b] += 0.25 * bass
    for start in np.arange(0, seconds, beat):
        a = int(start * SR)
        b = min(n, a + int(0.25 * SR))
        tt = t[a:b] - start
        kick = np.sin(2 * np.pi * (50 + 80 * np.exp(-tt * 30)) * tt) * np.exp(-tt * 18)
        hat = rng.standard_normal(b - a) * np.exp(-tt * 60) * 0.15
        left[a:b] += 0.5 * kick + hat
        right[a:b] += 0.5 * kick + 0.7 * hat
    vib = 1 + 0.01 * np.sin(2 * np.pi * 5 * t)
    voice = np.sin(2 * np.pi * 440 * vib * t) * (0.5 + 0.5 * np.sin(2 * np.pi * 0.25 * t)) ** 2
    left += 0.15 * voice
    right += 0.15 * voice
    mix = np.stack([left, right]).astype(np.float32)
    return (mix / np.max(np.abs(mix)) * 0.7).astype(np.float32)


mix = test_signal()
N = mix.shape[1]
ref = mix.mean(0)
mean, std = float(ref.mean()), float(ref.std(ddof=1))
wav = (mix - mean) / std

# ------------------------------------------------------------------ 1) a decomposição confere com o modelo inteiro
win = torch.from_numpy(np.ascontiguousarray(wav[None, :, :SEG]))
with torch.no_grad():
    full = model(win)
    z = ORIG["_spec"](win)
    mag = ORIG["_magnitude"](z).contiguous()
    freq, tbranch = core(win, mag)
    recon = tbranch + ORIG["_ispec"](ORIG["_mask"](z, freq), SEG)
print("decomposição vs modelo inteiro: SNR %.1f dB" % snr(full.numpy(), recon.numpy()))
assert snr(full.numpy(), recon.numpy()) > 90
assert tuple(mag.shape) == (1, 4, BINS, FRAMES), mag.shape
assert tuple(freq.shape) == (1, S, 4, BINS, FRAMES), freq.shape
assert tuple(tbranch.shape) == (1, S, 2, SEG), tbranch.shape

# ------------------------------------------------------------------ 2) exporta
# o exportador do PyTorch 2.0 não conhece aten::unflatten (usado na atenção); reshape faz o mesmo
def _unflatten(self, dim, sizes):
    dim = dim if dim >= 0 else self.dim() + dim
    return self.reshape(list(self.shape[:dim]) + list(sizes) + list(self.shape[dim + 1:]))


torch.Tensor.unflatten = _unflatten


# idem para scaled_dot_product_attention: a mesma conta, escrita com operações que exportam, e em blocos
# de consultas. Inteira, a matriz de atenção (8 cabeças x 2688 x 2688) fazia a rede passar de 2 GB de memória
# por trecho e o Android fechava o app; em blocos, cada pedaço é liberado antes do próximo.
ATT_BLOCK = 256


def _sdpa(q, k, v, attn_mask=None, dropout_p=0.0, is_causal=False, scale=None):
    assert not is_causal and dropout_p == 0.0
    q = q * (scale if scale is not None else 1.0 / math.sqrt(q.size(-1)))
    kt = k.transpose(-2, -1)
    n = q.shape[-2]
    outs = []
    for a in range(0, n, ATT_BLOCK):
        w = torch.matmul(q[..., a:a + ATT_BLOCK, :], kt)
        if attn_mask is not None:
            m = attn_mask[..., a:a + ATT_BLOCK, :] if attn_mask.dim() >= 2 and attn_mask.shape[-2] == n else attn_mask
            w = w.masked_fill(~m, float("-inf")) if m.dtype == torch.bool else w + m
        outs.append(torch.matmul(torch.softmax(w, dim=-1), v))
    return outs[0] if len(outs) == 1 else torch.cat(outs, dim=-2)


torch.nn.functional.scaled_dot_product_attention = _sdpa
for p in model.parameters():
    p.requires_grad_(True)  # com gradiente ligado o PyTorch não usa o atalho de atenção, que não exporta
with torch.no_grad():
    f2, t2 = core(win, mag)
print("com as trocas para exportar: SNR %.1f dB" % snr(freq.numpy(), f2.numpy()))
assert snr(freq.numpy(), f2.numpy()) > 90 and snr(tbranch.numpy(), t2.numpy()) > 90
raw_path = os.path.join(OUT, "work", NAME + "_raw.onnx")
t0 = time.time()
torch.onnx.export(core, (win, mag), raw_path, input_names=["mix", "mag"], output_names=["freq", "time"],
                  opset_version=17, do_constant_folding=True)
print("exportado em %.0f s" % (time.time() - t0))

# pesos float32 grandes vão para um arquivo à parte, contíguos (o app expande float16 -> float32 sem conhecer o grafo)
graph = onnx.load(raw_path)
blob = bytearray()
moved = 0
for init in graph.graph.initializer:
    if init.data_type != onnx.TensorProto.FLOAT or int(np.prod(init.dims)) < 64:
        continue
    arr = numpy_helper.to_array(init).astype("<f4")
    while len(blob) % 64:
        blob += b"\0"
    offset = len(blob)
    blob += arr.tobytes()
    init.ClearField("raw_data")
    init.ClearField("float_data")
    init.data_location = onnx.TensorProto.EXTERNAL
    del init.external_data[:]
    for key, value in (("location", DATA), ("offset", str(offset)), ("length", str(arr.nbytes))):
        entry = init.external_data.add()
        entry.key = key
        entry.value = value
    moved += 1
while len(blob) % 64:
    blob += b"\0"
weights32 = np.frombuffer(bytes(blob), "<f4")
assert np.all(np.isfinite(weights32))
assert np.max(np.abs(weights32)) < 60000, "peso fora do alcance do float16"
weights16 = weights32.astype("<f2")
print("pesos movidos: %d tensores, %.1f MB em float32" % (moved, len(blob) / 1e6))

model_dir = os.path.join(OUT, "model")
graph_path = os.path.join(model_dir, NAME + ".onnx")
onnx.save(graph, graph_path)
with open(os.path.join(model_dir, NAME + ".half"), "wb") as f:
    f.write(weights16.tobytes())

fp32_dir = os.path.join(OUT, "work", "fp32")
fp16_dir = os.path.join(OUT, "work", "fp16")
for d, w in ((fp32_dir, weights32), (fp16_dir, weights16.astype("<f4"))):
    os.makedirs(d, exist_ok=True)
    onnx.save(graph, os.path.join(d, NAME + ".onnx"))
    with open(os.path.join(d, DATA), "wb") as f:
        f.write(w.tobytes())
onnx.checker.check_model(os.path.join(fp32_dir, NAME + ".onnx"))

# ---- partes: mede o pico de memória com 1, 4, 8, 16 e 32 partes e fica com a menor divisão que cabe
import subprocess  # noqa: E402

typed = onnx.shape_inference.infer_shapes(onnx.load(graph_path, load_external_data=False))
probe_meta = {"segment": SEG, "bins": BINS, "frames": FRAMES}
measured = {}
for n_parts in (1, 4, 8, 16, 32):
    d = os.path.join(OUT, "work", "probe%d" % n_parts)
    os.makedirs(d, exist_ok=True)
    os.link(os.path.join(fp16_dir, DATA), os.path.join(d, DATA))
    probe_meta["parts"] = split(typed, n_parts, d, NAME)
    with open(os.path.join(d, "parts.json"), "w") as f:
        json.dump(probe_meta, f)
    r = subprocess.run([sys.executable, os.path.join(os.path.dirname(os.path.abspath(__file__)), "split.py"), d,
                        os.path.join(d, "parts.json")], capture_output=True, text=True)
    out = r.stdout.strip().split()
    measured[n_parts] = int(out[0]) if len(out) == 2 else 10 ** 9
    print("MEMORIA: %d partes -> pico %s MB, %s s/trecho %s" % (n_parts, out[0] if out else "?", out[1] if len(out) > 1 else "?",
                                                               "" if out else (r.stderr or "")[-300:]))
PARTS = next((n for n in sorted(measured) if measured[n] <= 600), min(measured, key=measured.get))
print("MEMORIA: escolhidas %d partes" % PARTS)
parts = None
for d in (model_dir, fp32_dir, fp16_dir):
    parts = split(typed, PARTS, d, NAME)
for d in (fp32_dir, fp16_dir):
    for p in parts:
        onnx.checker.check_model(os.path.join(d, p["file"]))

version = "%08x" % (int(np.frombuffer(weights16.tobytes()[: 1 << 20], np.uint32).sum()) & 0xFFFFFFFF)
with open(os.path.join(model_dir, NAME + ".json"), "w") as f:
    json.dump({"name": NAME, "version": version + "-" + str(len(blob)) + "-p" + str(PARTS), "sources": sources, "sampleRate": SR,
               "segment": SEG, "nfft": NFFT, "hop": HOP, "frames": FRAMES, "bins": BINS,
               "graph": NAME + ".onnx", "data": DATA, "weights": NAME + ".half", "parts": parts}, f, indent=1)

# ------------------------------------------------------------------ 3) ONNX Runtime vs PyTorch
feeds = {"mix": win.numpy(), "mag": mag.numpy()}
results = {}
for label, d in (("fp32", fp32_dir), ("fp16", fp16_dir)):
    sess = ort.InferenceSession(os.path.join(d, NAME + ".onnx"), providers=["CPUExecutionProvider"])
    whole_f, whole_t = sess.run(["freq", "time"], feeds)
    del sess
    t0 = time.time()
    of, ot = run_parts(d, parts, feeds)  # o caminho do app: as partes em sequência
    print("partes vs rede inteira: SNR %.1f dB" % min(snr(whole_f, of), snr(whole_t, ot)))
    assert min(snr(whole_f, of), snr(whole_t, ot)) > 100, "as partes não reproduzem a rede inteira"
    print("ORT %s: %.1f s por trecho de %.1f s" % (label, time.time() - t0, SEG / SR))
    results[label] = (of, ot)
    print("  freq SNR %.1f dB · tempo SNR %.1f dB" % (snr(freq.numpy(), of), snr(tbranch.numpy(), ot)))
    with torch.no_grad():
        out = torch.from_numpy(ot) + ORIG["_ispec"](ORIG["_mask"](z, torch.from_numpy(of)), SEG)
    print("  saída do trecho SNR %.1f dB" % snr(full.numpy(), out.numpy()))
    if label == "fp32":
        assert snr(full.numpy(), out.numpy()) > 60, "o ONNX não bate com o PyTorch"
    else:
        assert snr(full.numpy(), out.numpy()) > 30, "pesos em float16 estragaram o resultado"
        chunk_ort16 = out.numpy()[0]

# ------------------------------------------------------------------ 4) dados de teste para o Java
with torch.no_grad():
    stems = apply_model(bag, torch.from_numpy(wav)[None], shifts=0, split=True, overlap=0.25, progress=False)[0].numpy()
stems = stems * std + mean
print("separação de referência:", stems.shape, "soma vs mix SNR %.1f dB" % snr(mix, stems.sum(0)))

td = os.path.join(OUT, "testdata")
mix.astype("<f4").tofile(os.path.join(td, "mix.f32"))                      # [2][N]
stems.astype("<f4").tofile(os.path.join(td, "stems.f32"))                  # [S][2][N]
mag.numpy()[0].astype("<f4").tofile(os.path.join(td, "chunk0_mag.f32"))    # [4][BINS][FRAMES]
full.numpy()[0].astype("<f4").tofile(os.path.join(td, "chunk0_torch.f32"))  # [S][2][SEG]
chunk_ort16.astype("<f4").tofile(os.path.join(td, "chunk0_ort16.f32"))     # [S][2][SEG]
with open(os.path.join(td, "test.json"), "w") as f:
    json.dump({"frames": N, "sources": sources, "segment": SEG, "mean": mean, "std": std}, f, indent=1)
print("pronto")
