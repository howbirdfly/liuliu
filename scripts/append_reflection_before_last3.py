from __future__ import annotations

import sys
from pathlib import Path

from docx import Document


INSERTS = [
    "另外，我觉得这篇论文有一点特别值得注意，就是它并没有停留在“提出一个更快的锁”这一层面，而是真正考虑了这套机制在 Linux 这样复杂系统里能不能落地。很多论文中的优化方法在小型测试里效果很好，但一遇到内核中的特殊上下文、嵌套加锁、乱序解锁或者 per-CPU 变量，就很容易失效。这篇论文把这些现实问题摆在台面上，并尝试给出可行方案，这让我感觉它不仅有理论创新，也有很强的工程意识。",
    "从学习收获上来说，这篇论文让我重新理解了“同步机制”这一章的意义。以前在课堂上学锁，更多是从互斥、死锁和基本实现方式去理解，觉得锁主要是为了防止多个线程同时修改共享数据。看完这篇论文后，我意识到锁其实还深刻影响缓存行为、跨核通信成本和系统吞吐量。也就是说，同步机制不只是正确性工具，它本身也是性能设计的一部分，这一点对理解现代操作系统非常重要。",
    "我还觉得这篇论文体现了一个很好的研究思路：先从真实系统中的性能瓶颈出发，找到问题根源，再提出机制，最后通过完整实现和多层次实验来验证。相比单纯比较几个算法快慢，这种从“问题—设计—实现—验证”一路打通的方式更能说明一篇系统论文的价值。对我来说，这不仅帮助我理解了论文内容，也让我更清楚以后读操作系统论文时应该重点关注哪些部分。",
]


def main() -> int:
    if len(sys.argv) != 2:
        print("Usage: append_reflection_before_last3.py <docx-path>")
        return 1

    path = Path(sys.argv[1])
    doc = Document(str(path))
    non_empty = [p for p in doc.paragraphs if p.text.strip()]
    if len(non_empty) < 3:
        print("Document has fewer than 3 non-empty paragraphs.")
        return 1

    target = non_empty[-3]
    for text in reversed(INSERTS):
        para = target.insert_paragraph_before(text)
        try:
            para.style = target.style
        except Exception:
            pass

    doc.save(str(path))
    print(f"Updated: {path}")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
