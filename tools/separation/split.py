"""
Divide o grafo ONNX em partes que rodam uma depois da outra.

O ONNX Runtime só devolve a memória de trabalho de uma rede quando a execução termina; com a rede
inteira de uma vez, um trecho chegava a ~2 GB e o Android fechava o app. Em partes, cada execução
devolve a sua memória e só os tensores que passam de uma parte para as seguintes ficam guardados.
"""
import json
import os
import sys

import onnx
from onnx import helper


def split(model, n_parts, out_dir, prefix):
    g = model.graph
    nodes = list(g.node)
    init_names = {t.name for t in g.initializer}
    graph_outputs = [o.name for o in g.output]
    types = {}
    for vi in list(g.input) + list(g.output) + list(g.value_info):
        types[vi.name] = vi
    last_use = {}
    for i, node in enumerate(nodes):
        for x in node.input:
            if x:
                last_use[x] = i
    bounds = [round(k * len(nodes) / n_parts) for k in range(n_parts + 1)]
    parts = []
    for k in range(n_parts):
        a, b = bounds[k], bounds[k + 1]
        sub = nodes[a:b]
        produced = []
        for node in sub:
            produced.extend(o for o in node.output if o)
        produced_set = set(produced)
        needed = []
        for node in sub:
            for x in node.input:
                if x and x not in produced_set and x not in init_names and x not in needed:
                    needed.append(x)
        outs = [o for o in produced if last_use.get(o, -1) >= b or o in graph_outputs]
        inits = [t for t in g.initializer if any(t.name in node.input for node in sub)]

        def info(name):
            vi = types.get(name)
            if vi is not None:
                return vi
            return helper.make_tensor_value_info(name, onnx.TensorProto.FLOAT, None)

        graph = helper.make_graph(sub, "%s_p%d" % (prefix, k), [info(x) for x in needed], [info(o) for o in outs], initializer=inits)
        part = helper.make_model(graph, opset_imports=list(model.opset_import))
        part.ir_version = model.ir_version
        name = "%s.p%d.onnx" % (prefix, k)
        onnx.save(part, os.path.join(out_dir, name))
        parts.append({"file": name, "inputs": needed, "outputs": outs})
    return parts


def run_parts(directory, parts, feeds, threads=2, low_memory=True):
    """Roda as partes em sequência (como o app) e devolve as saídas finais."""
    import onnxruntime as ort
    o = ort.SessionOptions()
    o.intra_op_num_threads = threads
    if low_memory:
        o.enable_cpu_mem_arena = False
        o.enable_mem_pattern = False
    sessions = [ort.InferenceSession(os.path.join(directory, p["file"]), o, providers=["CPUExecutionProvider"]) for p in parts]
    last = {}
    for k, p in enumerate(parts):
        for x in p["inputs"]:
            last[x] = k
    values = dict(feeds)
    for k, (p, s) in enumerate(zip(parts, sessions)):
        res = s.run(p["outputs"], {x: values[x] for x in p["inputs"]})
        for name, v in zip(p["outputs"], res):
            values[name] = v
        for x in list(values):
            if last.get(x, len(parts)) <= k and x not in ("freq", "time"):
                del values[x]
    return values["freq"], values["time"]


if __name__ == "__main__":
    # sondagem de memória: python split.py <pasta> <partes.json> -> pico de RSS em MB
    import resource
    import numpy as np
    directory, meta = sys.argv[1], json.load(open(sys.argv[2]))
    feeds = {"mix": np.random.default_rng(0).standard_normal((1, 2, meta["segment"])).astype(np.float32),
             "mag": np.random.default_rng(1).standard_normal((1, 4, meta["bins"], meta["frames"])).astype(np.float32)}
    import onnxruntime  # noqa: F401  (carregado antes da medida base)
    import time

    def kb(field):
        for line in open("/proc/self/status"):
            if line.startswith(field + ":"):
                return int(line.split()[1])
        return 0

    base = kb("VmRSS")
    t = time.time()
    for _ in range(2):
        run_parts(directory, meta["parts"], feeds)
    print("%d %.1f" % ((kb("VmHWM") - base) // 1024, (time.time() - t) / 2))
