"""DocScan page detector: a small encoder-decoder that predicts the page region and its outline at 128 x 128 from a
256 x 256 picture. Built only from layers OpenCV's DNN module runs (convolutions, batch norm, ReLU, bilinear resize,
concatenation, sigmoid)."""
import torch, torch.nn as nn, torch.nn.functional as F

class Block(nn.Module):
    """Depthwise 3x3 (optionally strided) then pointwise 1x1, each with batch norm and ReLU; residual when shapes match."""
    def __init__(self, cin, cout, stride=1, dilation=1):
        super().__init__()
        self.dw = nn.Conv2d(cin, cin, 3, stride, padding=dilation, dilation=dilation, groups=cin, bias=False)
        self.bn1 = nn.BatchNorm2d(cin)
        self.pw = nn.Conv2d(cin, cout, 1, bias=False)
        self.bn2 = nn.BatchNorm2d(cout)
        self.residual = stride == 1 and cin == cout
    def forward(self, x):
        y = F.relu(self.bn1(self.dw(x)))
        y = self.bn2(self.pw(y))
        return F.relu(y + x) if self.residual else F.relu(y)

class Up(nn.Module):
    def __init__(self, cin, cskip, cout):
        super().__init__()
        self.fuse = nn.Sequential(nn.Conv2d(cin + cskip, cout, 1, bias=False), nn.BatchNorm2d(cout), nn.ReLU(inplace=True))
        self.block = Block(cout, cout)
    def forward(self, x, skip):
        x = F.interpolate(x, size=skip.shape[-2:], mode='bilinear', align_corners=False)
        return self.block(self.fuse(torch.cat([x, skip], 1)))

class PageNet(nn.Module):
    def __init__(self, w=1.0):
        super().__init__()
        c = [int(v * w) for v in (16, 32, 48, 80, 128)]
        self.stem = nn.Sequential(nn.Conv2d(3, c[0], 3, 2, 1, bias=False), nn.BatchNorm2d(c[0]), nn.ReLU(inplace=True))   # 128
        self.e1 = nn.Sequential(Block(c[0], c[1], 2), Block(c[1], c[1]))                                                     # 64
        self.e2 = nn.Sequential(Block(c[1], c[2], 2), Block(c[2], c[2]))                                                     # 32
        self.e3 = nn.Sequential(Block(c[2], c[3], 2), Block(c[3], c[3]), Block(c[3], c[3]))                                  # 16
        self.e4 = nn.Sequential(Block(c[3], c[4], 2), Block(c[4], c[4], dilation=2), Block(c[4], c[4], dilation=4), Block(c[4], c[4]))  # 8
        self.d3 = Up(c[4], c[3], c[3]); self.d2 = Up(c[3], c[2], c[2]); self.d1 = Up(c[2], c[1], c[1]); self.d0 = Up(c[1], c[0], c[0])
        self.head = nn.Conv2d(c[0], 2, 1)
    def forward(self, x):
        s0 = self.stem(x); s1 = self.e1(s0); s2 = self.e2(s1); s3 = self.e3(s2); s4 = self.e4(s3)
        y = self.d3(s4, s3); y = self.d2(y, s2); y = self.d1(y, s1); y = self.d0(y, s0)
        return self.head(y)   # logits, 2 x 128 x 128: page region, outline

class Exported(nn.Module):
    """What the app runs: BGR bytes 0..255 in, probabilities out."""
    def __init__(self, net):
        super().__init__(); self.net = net
    def forward(self, x):
        return torch.sigmoid(self.net(x / 255.0 - 0.5))

if __name__ == '__main__':
    n = PageNet(); print(sum(p.numel() for p in n.parameters()), 'parameters')
    print(n(torch.zeros(1, 3, 256, 256)).shape)
