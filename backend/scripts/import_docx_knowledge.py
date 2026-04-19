from __future__ import annotations

import json
import re
import shutil
import sys
from dataclasses import dataclass, field
from pathlib import Path
from zipfile import ZipFile
from xml.etree import ElementTree as ET

PROJECT_ROOT = Path(__file__).resolve().parents[1]
if str(PROJECT_ROOT) not in sys.path:
    sys.path.insert(0, str(PROJECT_ROOT))

from app.services.visual_features import image_embedding_from_path  # noqa: E402

DOCX_DEFAULT = PROJECT_ROOT.parent / "功能；提示词(1).docx"
KNOWLEDGE_ROOT = PROJECT_ROOT / "knowledge" / "docx_import"
ASSET_ROOT = PROJECT_ROOT / "knowledge_assets" / "docx_import"

NS = {
    "w": "http://schemas.openxmlformats.org/wordprocessingml/2006/main",
    "a": "http://schemas.openxmlformats.org/drawingml/2006/main",
    "r": "http://schemas.openxmlformats.org/officeDocument/2006/relationships",
}

SECTION_SLUGS = {
    "整体精神状态": "overall_mental",
    "典型异常姿势": "abnormal_posture",
    "被毛与外观异常": "coat_appearance",
    "呼吸与口鼻可视异常": "breathing_nose_mouth",
    "进食饮水行为": "eating_drinking",
    "自我护理与异常动作": "self_care_behavior",
    "呕吐相关可视行为": "vomit_behavior",
    "声音异常（音频）": "voice_abnormal",
}


@dataclass
class Entry:
    section_title: str
    section_slug: str
    title: str
    signal_text: str
    possible_causes: list[str] = field(default_factory=list)
    care_advice: list[str] = field(default_factory=list)
    image_targets: list[str] = field(default_factory=list)


def main() -> None:
    docx_path = DOCX_DEFAULT
    if len(sys.argv) > 1:
        docx_path = Path(sys.argv[1]).expanduser().resolve()
    if not docx_path.exists():
        raise SystemExit(f"docx not found: {docx_path}")

    parsed = parse_docx(docx_path)
    entries = build_entries(parsed)
    generate_outputs(docx_path, entries)
    print(f"imported_entries={len(entries)}")


def parse_docx(docx_path: Path) -> list[tuple[str, list[str]]]:
    with ZipFile(docx_path) as archive:
        rel_root = ET.fromstring(archive.read("word/_rels/document.xml.rels"))
        rels = {rel.attrib["Id"]: rel.attrib["Target"] for rel in rel_root}
        root = ET.fromstring(archive.read("word/document.xml"))
        body = root.find("w:body", NS)
        if body is None:
            return []
        ordered: list[tuple[str, list[str]]] = []
        for child in body:
            if child.tag.split("}")[-1] != "p":
                continue
            text = "".join(t.text or "" for t in child.findall(".//w:t", NS)).strip()
            images: list[str] = []
            for blip in child.findall(".//a:blip", NS):
                rid = blip.attrib.get(f"{{{NS['r']}}}embed")
                if rid and rid in rels:
                    images.append("word/" + rels[rid].lstrip("/"))
            if text or images:
                ordered.append((text, images))
        return ordered


def build_entries(items: list[tuple[str, list[str]]]) -> list[Entry]:
    entries: list[Entry] = []
    current_section_title = "综合异常"
    current_section_slug = "general_signals"
    current: Entry | None = None

    for text, images in items:
        section_match = re.match(r"^[一二三四五六七八九十]+、(.+)$", text)
        if section_match:
            if current is not None:
                entries.append(current)
                current = None
            current_section_title = section_match.group(1).strip()
            current_section_slug = SECTION_SLUGS.get(current_section_title, "general_signals")
            continue

        item_match = re.match(r"^\d+\.\s*([^：:]+?)(?:[：:](.+))?$", text)
        if item_match:
            if current is not None:
                entries.append(current)
            current = Entry(
                section_title=current_section_title,
                section_slug=current_section_slug,
                title=item_match.group(1).strip(),
                signal_text=(item_match.group(2) or item_match.group(1)).strip(),
            )
            continue

        if current is None:
            continue

        if text.startswith("——可能原因："):
            current.possible_causes = split_cn_list(text.removeprefix("——可能原因："))
            continue
        if text.startswith("——自查建议："):
            current.care_advice = split_cn_list(text.removeprefix("——自查建议："))
            continue
        if images:
            current.image_targets.extend(images)

    if current is not None:
        entries.append(current)
    return entries


def split_cn_list(text: str) -> list[str]:
    parts = re.split(r"[、；;，,]\s*", text.strip())
    return [part.strip() for part in parts if part.strip()]


def generate_outputs(docx_path: Path, entries: list[Entry]) -> None:
    with ZipFile(docx_path) as archive:
        if KNOWLEDGE_ROOT.exists():
            shutil.rmtree(KNOWLEDGE_ROOT)
        if ASSET_ROOT.exists():
            shutil.rmtree(ASSET_ROOT)
        KNOWLEDGE_ROOT.mkdir(parents=True, exist_ok=True)
        ASSET_ROOT.mkdir(parents=True, exist_ok=True)

        visual_records: list[dict[str, object]] = []
        section_counters: dict[str, int] = {}

        for entry in entries:
            section_counters[entry.section_slug] = section_counters.get(entry.section_slug, 0) + 1
            section_index = section_counters[entry.section_slug]
            doc_id = f"docx_{entry.section_slug}_{section_index:02d}"
            scene_hints = infer_scene_hints(entry)
            section_dir = KNOWLEDGE_ROOT / entry.section_slug
            section_dir.mkdir(parents=True, exist_ok=True)
            asset_dir = ASSET_ROOT / entry.section_slug / doc_id
            asset_dir.mkdir(parents=True, exist_ok=True)

            image_refs: list[str] = []
            exported_paths: list[Path] = []
            for image_index, target in enumerate(entry.image_targets, start=1):
                suffix = Path(target).suffix.lower() or ".jpg"
                exported_path = asset_dir / f"{doc_id}_{image_index:02d}{suffix}"
                exported_path.write_bytes(archive.read(target))
                rel_path = exported_path.relative_to(PROJECT_ROOT).as_posix()
                image_refs.append(rel_path)
                exported_paths.append(exported_path)

            for rel_path, exported_path in zip(image_refs, exported_paths):
                visual_records.append(
                    {
                        "doc_id": doc_id,
                        "title": entry.title,
                        "category": entry.section_slug,
                        "section": entry.section_title,
                        "scene_hints": scene_hints,
                        "tags": build_tags(entry, scene_hints),
                        "content": build_body(entry),
                        "possible_causes": entry.possible_causes,
                        "care_advice": entry.care_advice,
                        "image_refs": image_refs,
                        "matched_image_ref": rel_path,
                        "embedding": image_embedding_from_path(exported_path),
                    }
                )

            weight = "1.5" if any(flag in build_body(entry) for flag in ("尽快就医", "立即就医", "高风险", "急症")) else "1.1"
            markdown = "\n".join(
                [
                    "---",
                    f"doc_id: {doc_id}",
                    f"title: {entry.title}",
                    f"section: {entry.section_title}",
                    "tags: " + ", ".join(build_tags(entry, scene_hints)),
                    "scene_hints: " + ", ".join(scene_hints),
                    "keywords: " + " ".join(build_keywords(entry)),
                    f"weight: {weight}",
                    "possible_causes: " + "；".join(entry.possible_causes),
                    "care_advice: " + "；".join(entry.care_advice),
                    "image_refs: " + ", ".join(image_refs),
                    "---",
                    "",
                    build_body(entry),
                    "",
                ]
            )
            (section_dir / f"{doc_id}.md").write_text(markdown, encoding="utf-8")

        (KNOWLEDGE_ROOT / "visual_index.json").write_text(
            json.dumps(visual_records, ensure_ascii=False, indent=2),
            encoding="utf-8",
        )


def build_body(entry: Entry) -> str:
    lines = [f"观察表现：{entry.signal_text}"]
    if entry.possible_causes:
        lines.append("可能原因：" + "、".join(entry.possible_causes))
    if entry.care_advice:
        lines.append("自查建议：" + "；".join(entry.care_advice))
    return "\n".join(lines)


def infer_scene_hints(entry: Entry) -> list[str]:
    text = " ".join([entry.section_title, entry.title, entry.signal_text, *entry.possible_causes, *entry.care_advice])
    hints: list[str] = []
    if any(keyword in text for keyword in ("应激", "恐惧", "炸毛", "哈气", "情绪", "焦虑")):
        hints.append("stress")
    if any(keyword in text for keyword in ("进食", "喝水", "食", "口腔", "牙", "流口水", "甩头")):
        hints.append("appetite")
    if any(keyword in text for keyword in ("排尿", "尿闭", "猫砂", "蹲盆", "母鸡蹲", "泌尿")):
        hints.append("litterbox")
    if "general" not in hints:
        hints.append("general")
    return list(dict.fromkeys(hints))


def build_tags(entry: Entry, scene_hints: list[str]) -> list[str]:
    tags = [entry.section_title, entry.title, *scene_hints]
    tags.extend(entry.possible_causes[:3])
    return list(dict.fromkeys(tag for tag in tags if tag))


def build_keywords(entry: Entry) -> list[str]:
    keywords = [entry.title, entry.signal_text, *entry.possible_causes[:4], *entry.care_advice[:3]]
    return [kw.strip() for kw in keywords if kw.strip()]


if __name__ == "__main__":
    main()
