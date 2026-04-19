from __future__ import annotations

import json
import math
import re
from dataclasses import dataclass
from pathlib import Path

from app.domain.schemas import KnowledgeSnippet, PreparedMedia, SceneHint
from app.services.visual_features import cosine_similarity, image_embedding_from_media
from app.settings import settings

TOKEN_PATTERN = re.compile(r"[\u4e00-\u9fff]{1,4}|[a-zA-Z_]{2,}")

TOKEN_SYNONYMS: dict[str, tuple[str, ...]] = {
    "没精神": ("精神萎靡", "低活跃", "低能量"),
    "不精神": ("精神萎靡", "低活跃"),
    "蔫": ("精神萎靡",),
    "哈气": ("应激", "恐惧", "紧张"),
    "炸毛": ("应激", "恐惧"),
    "不吃": ("食欲差", "拒食", "持续不进食"),
    "没胃口": ("食欲差", "拒食"),
    "尿不出来": ("排尿困难", "无尿"),
    "蹲猫砂盆": ("频繁蹲猫砂盆", "排尿困难"),
    "喘": ("呼吸急促", "张口呼吸"),
    "抽": ("抽搐", "神经症状"),
    "软": ("低能量", "精神萎靡"),
}

RISK_INTENT_TERMS: tuple[str, ...] = (
    "排尿困难",
    "无尿",
    "张口呼吸",
    "呼吸急促",
    "抽搐",
    "持续不进食",
    "精神萎靡",
)

NEGATION_TERMS: tuple[str, ...] = (
    "没有",
    "没",
    "未",
    "无",
    "并未",
    "未见",
)

TEMPORAL_DURATION_TERMS: tuple[str, ...] = (
    "持续",
    "反复",
    "一直",
    "超过",
    "多天",
    "12小时",
    "24小时",
)

TEMPORAL_SUDDEN_TERMS: tuple[str, ...] = (
    "突然",
    "突发",
    "一下子",
    "刚刚",
    "骤然",
)

SEVERITY_HIGH_TERMS: tuple[str, ...] = (
    "明显",
    "严重",
    "剧烈",
    "频繁",
    "越来越重",
)

SEVERITY_LOW_TERMS: tuple[str, ...] = (
    "轻微",
    "偶尔",
    "一点",
    "稍微",
    "暂时",
)

SCENE_QUERY_EXTRA: dict[SceneHint, str] = {
    SceneHint.STRESS: "应激 紧张 恐惧 躲藏 哈气 炸毛 耳朵后压",
    SceneHint.APPETITE: "进食 食欲 挑食 消瘦 不吃 进食量 口炎 喝水",
    SceneHint.LITTERBOX: "猫砂 排尿 尿频 尿团 蹲盆 泌尿 母鸡蹲",
    SceneHint.GENERAL: "精神 活动 姿势 呼吸 疼痛 健康",
}

_FRONT_MATTER = re.compile(
    r"^\s*---\s*\r?\n(?P<meta>.*?)\r?\n---\s*\r?\n(?P<body>.*)\Z",
    re.DOTALL,
)

SCENE_CORE_TERMS: dict[SceneHint, tuple[str, ...]] = {
    SceneHint.STRESS: ("应激", "紧张", "恐惧", "哈气", "炸毛", "后压", "躲藏"),
    SceneHint.APPETITE: ("食欲", "进食", "挑食", "拒食", "不吃", "喝水"),
    SceneHint.LITTERBOX: ("猫砂", "排尿", "尿", "蹲盆", "泌尿", "尿团"),
    SceneHint.GENERAL: ("精神", "活动", "姿势", "呼吸", "疼痛", "状态"),
}


@dataclass(slots=True)
class KnowledgeDocument:
    doc_id: str
    title: str
    category: str
    section: str
    content: str
    tags: list[str]
    path: Path
    scene_hints: frozenset[str]
    weight: float
    retrieval_extra: str
    possible_causes: list[str]
    care_advice: list[str]
    image_refs: list[str]


@dataclass(slots=True)
class VisualKnowledgeRecord:
    doc_id: str
    title: str
    category: str
    section: str
    scene_hints: frozenset[str]
    tags: list[str]
    content: str
    possible_causes: list[str]
    care_advice: list[str]
    image_refs: list[str]
    matched_image_ref: str
    embedding: list[float]


class KnowledgeRetriever:
    """从 `knowledge/**/*.md` 加载条目；支持可选 frontmatter 与视觉索引。"""

    def __init__(self, knowledge_dir: Path | None = None) -> None:
        self.knowledge_dir = knowledge_dir or settings.knowledge_dir
        self._documents: list[KnowledgeDocument] = []
        self._visual_records: list[VisualKnowledgeRecord] = []
        self._sources_signature: float = 0.0

    def reload(self) -> None:
        self._sources_signature = 0.0
        self._ensure_fresh()

    def retrieve(
        self,
        user_text: str,
        scene_hint: SceneHint = SceneHint.GENERAL,
        model_tags: list[str] | None = None,
        limit: int = 5,
        media: PreparedMedia | None = None,
    ) -> list[KnowledgeSnippet]:
        self._ensure_fresh()
        model_tags = model_tags or []
        user_tokens_raw = self._expand_query_tokens(self._tokenize(user_text))
        relevant_model_tags = self._select_relevant_model_tags(
            model_tags=model_tags,
            scene_hint=scene_hint,
            user_tokens=user_tokens_raw,
        )
        extra = SCENE_QUERY_EXTRA.get(scene_hint, "")
        query = " ".join([user_text, scene_hint.value, extra, *relevant_model_tags]).strip()
        query_tokens = self._expand_query_tokens(self._tokenize(query))
        query_corpus = " ".join([user_text, *relevant_model_tags])
        negated_terms = self._extract_negated_terms(user_text, model_tags=relevant_model_tags)
        negated_tokens = set(self._expand_query_tokens(self._tokenize(" ".join(sorted(negated_terms)))))
        filtered_query_tokens = [token for token in query_tokens if token not in negated_tokens]
        if not filtered_query_tokens:
            filtered_query_tokens = query_tokens
        model_tag_tokens = set(self._expand_query_tokens(self._tokenize(" ".join(relevant_model_tags))))
        risk_terms = {
            term
            for term in RISK_INTENT_TERMS
            if term in " ".join([user_text, *relevant_model_tags]) and term not in negated_terms
        }
        query_signals = self._extract_query_signals(query_corpus)
        scene_signal_strength = self._scene_signal_strength(user_text, scene_hint=scene_hint)

        text_hits = self._retrieve_text(
            query_tokens=filtered_query_tokens,
            scene_hint=scene_hint,
            model_tag_tokens=model_tag_tokens,
            risk_terms=risk_terms,
            negated_terms=negated_terms,
            query_signals=query_signals,
            scene_signal_strength=scene_signal_strength,
            limit=max(limit * 2, 6),
        )
        visual_hits = self._retrieve_visual(
            query_tokens=filtered_query_tokens,
            scene_hint=scene_hint,
            model_tag_tokens=model_tag_tokens,
            negated_terms=negated_terms,
            query_signals=query_signals,
            scene_signal_strength=scene_signal_strength,
            media=media,
            limit=min(3, max(1, limit // 2)),
        )
        merged = self._merge_hits(text_hits, visual_hits)
        return merged[:limit]

    def _retrieve_text(
        self,
        *,
        query_tokens: list[str],
        scene_hint: SceneHint,
        model_tag_tokens: set[str],
        risk_terms: set[str],
        negated_terms: set[str],
        query_signals: dict[str, bool],
        scene_signal_strength: int,
        limit: int,
    ) -> list[KnowledgeSnippet]:
        ranked: list[KnowledgeSnippet] = []
        for document in self._documents:
            score = self._score(
                document,
                query_tokens,
                scene_hint,
                model_tag_tokens=model_tag_tokens,
                risk_terms=risk_terms,
                negated_terms=negated_terms,
                query_signals=query_signals,
                scene_signal_strength=scene_signal_strength,
            )
            if score <= 0:
                continue
            ranked.append(self._snippet_from_document(document, score=score))
        ranked.sort(key=lambda item: item.score, reverse=True)
        return ranked[:limit]

    def _retrieve_visual(
        self,
        *,
        query_tokens: list[str],
        scene_hint: SceneHint,
        model_tag_tokens: set[str],
        negated_terms: set[str],
        query_signals: dict[str, bool],
        scene_signal_strength: int,
        media: PreparedMedia | None,
        limit: int,
    ) -> list[KnowledgeSnippet]:
        if media is None or not self._visual_records:
            return []
        query_embedding = image_embedding_from_media(media)
        if query_embedding is None:
            return []

        ranked: list[KnowledgeSnippet] = []
        for record in self._visual_records:
            similarity = cosine_similarity(query_embedding, record.embedding)
            if similarity <= 0.08:
                continue
            score = max(similarity, 0.0) * 2.2
            if scene_hint.value in record.scene_hints:
                score += 0.45
            if query_tokens:
                record_tokens = self._expand_query_tokens(self._tokenize(" ".join([record.title, record.content, *record.tags])))
                overlap = len(set(query_tokens) & set(record_tokens))
                score += min(0.45, overlap * 0.08)
                if model_tag_tokens:
                    tag_overlap = len(model_tag_tokens & set(record_tokens))
                    score += min(0.35, tag_overlap * 0.12)
            if negated_terms:
                record_text = " ".join([record.title, record.content, " ".join(record.tags), " ".join(record.possible_causes)])
                negated_hits = sum(1 for term in negated_terms if term in record_text)
                score -= min(0.7, negated_hits * 0.25)
            if query_signals.get("has_duration"):
                record_text = " ".join([record.title, record.content, " ".join(record.tags)])
                duration_hits = sum(1 for term in TEMPORAL_DURATION_TERMS if term in record_text)
                score += min(0.35, duration_hits * 0.12)
            if query_signals.get("high_intensity"):
                record_text = " ".join([record.title, record.content, " ".join(record.tags)])
                if any(term in record_text for term in ("高风险", "急症", "尽快就医", "排尿困难", "张口呼吸")):
                    score += 0.2
            if query_signals.get("low_intensity"):
                record_text = " ".join([record.title, record.content, " ".join(record.tags)])
                if any(term in record_text for term in ("高风险", "急症", "尽快就医")):
                    score -= 0.15
            if scene_signal_strength >= 2 and record.scene_hints and scene_hint.value not in record.scene_hints:
                score -= 0.4
            if score <= 0:
                continue
            ranked.append(
                KnowledgeSnippet(
                    doc_id=record.doc_id,
                    title=record.title,
                    category=record.category,
                    content=record.content,
                    score=round(score, 4),
                    tags=record.tags,
                    source_type="visual",
                    section=record.section,
                    possible_causes=record.possible_causes,
                    care_advice=record.care_advice,
                    image_refs=record.image_refs,
                    matched_image_ref=record.matched_image_ref,
                )
            )
        ranked.sort(key=lambda item: item.score, reverse=True)
        return ranked[:limit]

    def _merge_hits(
        self,
        text_hits: list[KnowledgeSnippet],
        visual_hits: list[KnowledgeSnippet],
    ) -> list[KnowledgeSnippet]:
        merged: dict[str, KnowledgeSnippet] = {}
        for item in text_hits + visual_hits:
            existing = merged.get(item.doc_id)
            if existing is None:
                merged[item.doc_id] = item.model_copy(deep=True)
                continue
            existing.score = round(existing.score + item.score * 0.7, 4)
            existing.tags = list(dict.fromkeys([*existing.tags, *item.tags]))
            existing.possible_causes = list(dict.fromkeys([*existing.possible_causes, *item.possible_causes]))
            existing.care_advice = list(dict.fromkeys([*existing.care_advice, *item.care_advice]))
            existing.image_refs = list(dict.fromkeys([*existing.image_refs, *item.image_refs]))
            if item.matched_image_ref:
                existing.matched_image_ref = item.matched_image_ref
            if {existing.source_type, item.source_type} == {"text", "visual"}:
                existing.source_type = "hybrid"
                existing.score = round(existing.score + 0.35, 4)
        ranked = list(merged.values())
        ranked.sort(key=lambda item: item.score, reverse=True)
        if ranked:
            top = max(ranked[0].score, 1e-6)
            for item in ranked:
                item.score = round(max(0.0, min(1.0, item.score / top)), 4)
        return ranked

    def _ensure_fresh(self) -> None:
        if not self.knowledge_dir.exists():
            self._documents = []
            self._visual_records = []
            self._sources_signature = 0.0
            return

        signature = self._directory_signature()
        if signature != self._sources_signature:
            self._documents = self._load_documents()
            self._visual_records = self._load_visual_records()
            self._sources_signature = signature

    def _directory_signature(self) -> float:
        mtimes: list[float] = []
        for path in self.knowledge_dir.rglob("*"):
            if path.is_dir():
                continue
            if path.suffix.lower() == ".md" and self._should_skip_path(path):
                continue
            if path.suffix.lower() not in {".md", ".json"}:
                continue
            try:
                mtimes.append(path.stat().st_mtime)
            except OSError:
                continue
        return max(mtimes, default=0.0)

    @staticmethod
    def _should_skip_path(path: Path) -> bool:
        name = path.name
        if name.startswith("_"):
            return True
        if name.lower() == "readme.md":
            return True
        return False

    def _load_documents(self) -> list[KnowledgeDocument]:
        documents: list[KnowledgeDocument] = []
        if not self.knowledge_dir.exists():
            return documents

        for path in sorted(self.knowledge_dir.rglob("*.md")):
            if self._should_skip_path(path):
                continue
            try:
                raw = path.read_text(encoding="utf-8")
            except OSError:
                continue
            meta, body = self._split_front_matter(raw)
            title_default = path.stem.replace("_", " ").strip()
            category = path.parent.name
            section = (meta.get("section") or category or title_default).strip()
            title = (meta.get("title") or title_default).strip()
            tag_parts = self._split_list_field(meta.get("tags", ""))
            scene_raw = self._split_list_field(meta.get("scene_hints") or meta.get("scenes") or "")
            scene_hints = frozenset(s.strip().lower() for s in scene_raw if s.strip())

            weight = 1.0
            if w := meta.get("weight"):
                try:
                    weight = max(0.1, min(5.0, float(w)))
                except ValueError:
                    weight = 1.0

            retrieval_extra = " ".join(
                [
                    meta.get("keywords", ""),
                    meta.get("alias", ""),
                    meta.get("signals", ""),
                ]
            ).strip()
            possible_causes = self._split_list_field(meta.get("possible_causes", ""))
            care_advice = self._split_list_field(meta.get("care_advice", ""))
            image_refs = self._split_list_field(meta.get("image_refs", ""))

            tags = list(dict.fromkeys(filter(None, [category, section, title, *tag_parts])))
            doc_id = (meta.get("doc_id") or f"{category}_{path.stem}").strip()
            content = body.strip()
            documents.append(
                KnowledgeDocument(
                    doc_id=doc_id,
                    title=title,
                    category=category,
                    section=section,
                    content=content,
                    tags=tags,
                    path=path,
                    scene_hints=scene_hints,
                    weight=weight,
                    retrieval_extra=retrieval_extra,
                    possible_causes=possible_causes,
                    care_advice=care_advice,
                    image_refs=image_refs,
                )
            )
        return documents

    def _load_visual_records(self) -> list[VisualKnowledgeRecord]:
        records: list[VisualKnowledgeRecord] = []
        for path in sorted(self.knowledge_dir.rglob("visual_index.json")):
            try:
                payload = json.loads(path.read_text(encoding="utf-8"))
            except (OSError, json.JSONDecodeError):
                continue
            for item in payload:
                image_path = item.get("matched_image_ref") or item.get("image_path") or ""
                records.append(
                    VisualKnowledgeRecord(
                        doc_id=item["doc_id"],
                        title=item["title"],
                        category=item.get("category", path.parent.name),
                        section=item.get("section", path.parent.name),
                        scene_hints=frozenset(s.lower() for s in item.get("scene_hints", [])),
                        tags=item.get("tags", []),
                        content=item.get("content", ""),
                        possible_causes=item.get("possible_causes", []),
                        care_advice=item.get("care_advice", []),
                        image_refs=item.get("image_refs", []),
                        matched_image_ref=image_path,
                        embedding=item.get("embedding", []),
                    )
                )
        return records

    def _snippet_from_document(self, document: KnowledgeDocument, *, score: float) -> KnowledgeSnippet:
        return KnowledgeSnippet(
            doc_id=document.doc_id,
            title=document.title,
            category=document.category,
            content=document.content,
            score=score,
            tags=document.tags,
            source_type="text",
            section=document.section,
            possible_causes=document.possible_causes,
            care_advice=document.care_advice,
            image_refs=document.image_refs,
        )

    @staticmethod
    def _split_front_matter(raw: str) -> tuple[dict[str, str], str]:
        m = _FRONT_MATTER.match(raw.strip())
        if not m:
            return {}, raw.strip()
        meta_lines = m.group("meta").strip().splitlines()
        meta: dict[str, str] = {}
        for line in meta_lines:
            if ":" not in line:
                continue
            key, value = line.split(":", 1)
            meta[key.strip().lower()] = value.strip()
        return meta, m.group("body")

    @staticmethod
    def _split_list_field(value: str) -> list[str]:
        if not value:
            return []
        return [part.strip() for part in re.split(r"[,，;；|\n]+", value) if part.strip()]

    def _score(
        self,
        document: KnowledgeDocument,
        query_tokens: list[str],
        scene_hint: SceneHint,
        *,
        model_tag_tokens: set[str],
        risk_terms: set[str],
        negated_terms: set[str],
        query_signals: dict[str, bool],
        scene_signal_strength: int,
    ) -> float:
        if not query_tokens:
            return 0.0

        title_tokens = self._expand_query_tokens(self._tokenize(document.title))
        tag_tokens = self._expand_query_tokens(self._tokenize(" ".join(document.tags)))
        extra_tokens = self._expand_query_tokens(self._tokenize(document.retrieval_extra))
        content_tokens = self._expand_query_tokens(
            self._tokenize(
                " ".join(
                    [
                        document.content,
                        " ".join(document.possible_causes),
                        " ".join(document.care_advice),
                    ]
                )
            )
        )
        text_for_tokens = " ".join(
            [
                document.title,
                document.category,
                document.section,
                " ".join(document.tags),
                document.content,
                " ".join(document.possible_causes),
                " ".join(document.care_advice),
                document.retrieval_extra,
            ]
        )
        doc_tokens = [*title_tokens, *tag_tokens, *extra_tokens, *content_tokens]
        overlap = sum(min(query_tokens.count(token), doc_tokens.count(token)) for token in set(query_tokens))
        if overlap == 0:
            return 0.0

        query_set = set(query_tokens)
        title_overlap = len(query_set & set(title_tokens))
        tag_overlap = len(query_set & set(tag_tokens))
        extra_overlap = len(query_set & set(extra_tokens))
        content_overlap = len(query_set & set(content_tokens))

        score = overlap / math.sqrt(max(len(doc_tokens), 1))
        score += title_overlap * 0.35
        score += tag_overlap * 0.22
        score += extra_overlap * 0.18
        score += content_overlap * 0.08
        score *= document.weight

        if scene_hint.value in document.doc_id or any(scene_hint.value == hint for hint in document.scene_hints):
            score += 1.5

        if model_tag_tokens:
            tag_match = len(model_tag_tokens & set(doc_tokens))
            score += min(0.8, tag_match * 0.2)

        if risk_terms:
            risk_matches = sum(1 for term in risk_terms if term in text_for_tokens)
            score += min(0.9, risk_matches * 0.3)

        if negated_terms:
            negated_matches = sum(1 for term in negated_terms if term in text_for_tokens)
            score -= min(1.1, negated_matches * 0.35)

        if query_signals.get("has_duration"):
            duration_matches = sum(1 for term in TEMPORAL_DURATION_TERMS if term in text_for_tokens)
            score += min(0.5, duration_matches * 0.16)

        if query_signals.get("has_sudden"):
            sudden_matches = sum(1 for term in TEMPORAL_SUDDEN_TERMS if term in text_for_tokens)
            score += min(0.4, sudden_matches * 0.14)

        is_high_risk_doc = any(tag in text_for_tokens for tag in ("尽快就医", "高风险", "排尿困难", "张口呼吸", "急症"))
        is_observe_doc = any(tag in text_for_tokens for tag in ("继续观察", "轻度", "偶发", "暂时", "先观察"))
        if query_signals.get("high_intensity") and is_high_risk_doc:
            score += 0.28
        if query_signals.get("low_intensity"):
            if is_high_risk_doc:
                score -= 0.22
            if is_observe_doc:
                score += 0.14
        if scene_signal_strength >= 2 and document.scene_hints and scene_hint.value not in document.scene_hints:
            score -= 0.55

        if any(tag in text_for_tokens for tag in ("尽快就医", "高风险", "排尿困难", "张口呼吸", "急症")):
            score += 0.2
        if document.image_refs:
            score += 0.05
        return round(score, 4)

    @staticmethod
    def _tokenize(text: str) -> list[str]:
        return [match.group(0).lower() for match in TOKEN_PATTERN.finditer(text)]

    @staticmethod
    def _expand_query_tokens(tokens: list[str]) -> list[str]:
        expanded: list[str] = []
        synonym_cache = {
            normalized.lower(): tuple(alias.lower() for alias in aliases) for normalized, aliases in TOKEN_SYNONYMS.items()
        }
        for token in tokens:
            expanded.append(token)
            for normalized, aliases in synonym_cache.items():
                if token == normalized or token in aliases:
                    expanded.append(normalized)
                    expanded.extend(aliases)
        return expanded

    @staticmethod
    def _extract_negated_terms(user_text: str, *, model_tags: list[str]) -> set[str]:
        corpus = " ".join([user_text, *model_tags])
        negated: set[str] = set()
        for term in RISK_INTENT_TERMS:
            if any(f"{neg}{term}" in corpus for neg in NEGATION_TERMS):
                negated.add(term)
                continue
            if any(f"{term}{suffix}" in corpus for suffix in ("没有", "未见", "不明显", "并无")):
                negated.add(term)
        for colloquial, aliases in TOKEN_SYNONYMS.items():
            if any(f"{neg}{colloquial}" in corpus for neg in NEGATION_TERMS):
                negated.add(colloquial)
                negated.update(aliases)
        return negated

    @staticmethod
    def _extract_query_signals(corpus: str) -> dict[str, bool]:
        return {
            "has_duration": any(term in corpus for term in TEMPORAL_DURATION_TERMS),
            "has_sudden": any(term in corpus for term in TEMPORAL_SUDDEN_TERMS),
            "high_intensity": any(term in corpus for term in SEVERITY_HIGH_TERMS),
            "low_intensity": any(term in corpus for term in SEVERITY_LOW_TERMS),
        }

    def _select_relevant_model_tags(
        self,
        *,
        model_tags: list[str],
        scene_hint: SceneHint,
        user_tokens: list[str],
    ) -> list[str]:
        if not model_tags:
            return []
        user_token_set = set(user_tokens)
        scene_tokens = set(self._expand_query_tokens(self._tokenize(" ".join(SCENE_CORE_TERMS.get(scene_hint, ())))))
        selected: list[str] = []
        for tag in model_tags:
            tag_tokens = set(self._expand_query_tokens(self._tokenize(tag)))
            if not tag_tokens:
                continue
            if tag_tokens & user_token_set or tag_tokens & scene_tokens:
                selected.append(tag)
        return selected

    def _scene_signal_strength(self, user_text: str, *, scene_hint: SceneHint) -> int:
        scene_terms = SCENE_CORE_TERMS.get(scene_hint, ())
        if not scene_terms:
            return 0
        corpus = user_text.lower()
        return sum(1 for term in scene_terms if term and term in corpus)


retriever = KnowledgeRetriever()
