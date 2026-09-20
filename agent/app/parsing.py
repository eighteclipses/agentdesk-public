"""Small document parsing adapter used before knowledge indexing.

PDF 产物在页与页之间插入 ``<!-- page:N -->`` 标记，后端 Chunker 用它回填
chunk 的页码，使引用可以定位到 PDF 的具体页。
"""
from __future__ import annotations

from io import BytesIO
from pathlib import PurePath


def parse_to_markdown(filename: str, content: bytes) -> tuple[str, str]:
    suffix = PurePath(filename).suffix.lower()
    if suffix in {".md", ".markdown", ".txt"}:
        return content.decode("utf-8", errors="replace"), "text"
    if suffix == ".pdf":
        from pypdf import PdfReader
        reader = PdfReader(BytesIO(content))
        pages: list[str] = []
        for number, page in enumerate(reader.pages, start=1):
            text = (page.extract_text() or "").strip()
            if text:
                pages.append(f"<!-- page:{number} -->\n\n{text}")
        if not pages:
            raise ValueError(
                "PDF 中没有可提取的文本层，疑似扫描件；P2 接入 OCR 前请先手动 OCR 或改用文本版 PDF"
            )
        return "\n\n".join(pages).strip(), "pdf"
    if suffix == ".docx":
        from docx import Document
        document = Document(BytesIO(content))
        blocks: list[str] = []
        for block in _iter_blocks(document):
            if isinstance(block, _TableBlock):
                blocks.append(_table_to_markdown(block.rows))
            elif (text := block.strip()):
                blocks.append(text)
        if not blocks:
            raise ValueError("DOCX 中没有可提取的正文段落或表格")
        return "\n\n".join(blocks), "docx"
    raise ValueError(f"暂不支持 {suffix or '无扩展名'} 文件，请先转换为 Markdown、TXT、PDF 或 DOCX")


class _TableBlock:
    """docx 表格的轻量包装：仅保留单元格文本行。"""
    def __init__(self, rows: list[list[str]]):
        self.rows = rows


def _iter_blocks(document) -> list[object]:
    """按文档顺序产出段落文本与表格：默认 document.paragraphs 会丢掉表格内容。"""
    from docx.oxml.ns import qn
    from docx.table import Table
    from docx.text.paragraph import Paragraph

    blocks: list[object] = []
    for child in document.element.body.iterchildren():
        if child.tag == qn("w:p"):
            blocks.append(Paragraph(child, document).text.strip())
        elif child.tag == qn("w:tbl"):
            table = Table(child, document)
            rows = [[cell.text.strip() for cell in row.cells] for row in table.rows]
            blocks.append(_TableBlock(rows))
    return blocks


def _table_to_markdown(rows: list[list[str]]) -> str:
    """表格转 Markdown 管道表：首行作表头；宽度不齐的行按列数补空。"""
    cleaned = [row for row in rows if any(cell for cell in row)]
    if not cleaned:
        return ""
    width = max(len(row) for row in cleaned)
    padded = [row + [""] * (width - len(row)) for row in cleaned]
    out = ["| " + " | ".join(padded[0]) + " |", "| " + " | ".join(["---"] * width) + " |"]
    for row in padded[1:]:
        out.append("| " + " | ".join(cell.replace("\n", " ").replace("|", "\\|") for cell in row) + " |")
    return "\n".join(out)
