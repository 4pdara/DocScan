"""Exports a PageNet checkpoint to ONNX (BGR bytes in, page and outline probabilities out) and checks it."""
import os, sys, numpy as np, torch
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from model import PageNet, Exported
ckpt, out = sys.argv[1], sys.argv[2]; width = float(os.environ.get('WIDTH', '1.0'))
net = PageNet(width)
if ckpt != 'none': net.load_state_dict(torch.load(ckpt))
net.eval(); model = Exported(net).eval()
x = torch.rand(1, 3, 256, 256) * 255
torch.onnx.export(model, x, out, input_names=['image'], output_names=['page'], opset_version=11, do_constant_folding=True, dynamo=False)
import onnxruntime as ort
ref = model(x).detach().numpy(); got = ort.InferenceSession(out).run(None, {'image': x.numpy()})[0]
print('onnx size', os.path.getsize(out), 'max diff vs torch', float(np.abs(ref - got).max()))
np.save(out + '.input.npy', x.numpy()); np.save(out + '.ref.npy', ref)
