import base64
import io
import json
from pathlib import Path

from PIL import Image

from app.domain.schemas import MediaType, PreparedMedia, SceneHint
from app.services.retriever import KnowledgeRetriever
from app.services.visual_features import image_embedding_from_path


def test_retriever_skips_readme_and_underscore_files(tmp_path: Path) -> None:
    kb = tmp_path / "health"
    kb.mkdir()
    (kb / "README.md").write_text("# ignored", encoding="utf-8")
    (kb / "_draft.md").write_text("正文", encoding="utf-8")
    (kb / "visible.md").write_text("猫咪排尿困难需重视", encoding="utf-8")

    r = KnowledgeRetriever(knowledge_dir=tmp_path)
    r.reload()
    assert len(r._documents) == 1
    assert r._documents[0].doc_id == "health_visible"


def test_retriever_frontmatter_boosts_scene(tmp_path: Path) -> None:
    kb = tmp_path / "emotion"
    kb.mkdir()
    (kb / "custom.md").write_text(
        "---\n"
        "title: 测试应激\n"
        "scene_hints: stress\n"
        "keywords: 哈气 躲藏\n"
        "weight: 1.5\n"
        "---\n"
        "\n"
        "应激时可能躲藏。",
        encoding="utf-8",
    )
    r = KnowledgeRetriever(knowledge_dir=tmp_path)
    r.reload()
    hits = r.retrieve("猫哈气", scene_hint=SceneHint.STRESS, limit=3)
    assert hits
    assert hits[0].doc_id == "emotion_custom"
    assert hits[0].title == "测试应激"


def test_retriever_returns_visual_hit_when_media_matches_index(tmp_path: Path) -> None:
    kb = tmp_path / "stress"
    kb.mkdir()
    image_dir = tmp_path / "assets"
    image_dir.mkdir()

    image_path = image_dir / "stress-cat.jpg"
    Image.new("RGB", (64, 64), color=(210, 160, 120)).save(image_path, format="JPEG")

    (kb / "stress_case.md").write_text(
        "---\n"
        "doc_id: stress_case\n"
        "title: 应激炸毛\n"
        "scene_hints: stress\n"
        "possible_causes: 疼痛；环境刺激\n"
        "care_advice: 减少刺激；安静观察\n"
        "image_refs: assets/stress-cat.jpg\n"
        "---\n"
        "\n"
        "观察表现：猫咪炸毛、耳朵后压。\n"
        "自查建议：减少环境刺激。",
        encoding="utf-8",
    )
    (tmp_path / "visual_index.json").write_text(
        json.dumps(
            [
                {
                    "doc_id": "stress_case",
                    "title": "应激炸毛",
                    "category": "stress",
                    "section": "整体精神状态",
                    "scene_hints": ["stress", "general"],
                    "tags": ["炸毛", "耳朵后压"],
                    "content": "观察表现：猫咪炸毛、耳朵后压。",
                    "possible_causes": ["疼痛", "环境刺激"],
                    "care_advice": ["减少刺激", "安静观察"],
                    "image_refs": ["assets/stress-cat.jpg"],
                    "matched_image_ref": "assets/stress-cat.jpg",
                    "embedding": image_embedding_from_path(image_path),
                }
            ],
            ensure_ascii=False,
        ),
        encoding="utf-8",
    )

    buffer = io.BytesIO()
    Image.new("RGB", (64, 64), color=(210, 160, 120)).save(buffer, format="JPEG")
    media = PreparedMedia(
        media_type=MediaType.IMAGE,
        mime_type="image/jpeg",
        data_url="data:image/jpeg;base64," + base64.b64encode(buffer.getvalue()).decode("utf-8"),
    )

    r = KnowledgeRetriever(knowledge_dir=tmp_path)
    r.reload()
    hits = r.retrieve("猫咪很紧张", scene_hint=SceneHint.STRESS, media=media, limit=3)
    assert hits
    assert hits[0].doc_id == "stress_case"
    assert hits[0].source_type in {"visual", "hybrid"}
    assert hits[0].matched_image_ref == "assets/stress-cat.jpg"


def test_retriever_boosts_synonym_query_for_low_energy(tmp_path: Path) -> None:
    kb = tmp_path / "general"
    kb.mkdir()
    (kb / "low_energy.md").write_text(
        "---\n"
        "title: 精神萎靡\n"
        "scene_hints: general\n"
        "tags: 低能量, 不愿活动\n"
        "---\n"
        "\n"
        "观察表现：精神萎靡、长时间趴卧不动。",
        encoding="utf-8",
    )
    (kb / "playful.md").write_text(
        "---\n"
        "title: 活跃玩耍\n"
        "scene_hints: general\n"
        "tags: 玩耍, 兴奋\n"
        "---\n"
        "\n"
        "观察表现：追逐玩具、互动积极。",
        encoding="utf-8",
    )

    r = KnowledgeRetriever(knowledge_dir=tmp_path)
    r.reload()
    hits = r.retrieve("今天有点没精神", scene_hint=SceneHint.GENERAL, limit=2)
    assert hits
    assert hits[0].doc_id == "general_low_energy"


def test_retriever_boosts_model_tags_for_urination_risk(tmp_path: Path) -> None:
    kb = tmp_path / "litterbox"
    kb.mkdir()
    (kb / "urination.md").write_text(
        "---\n"
        "title: 排尿困难\n"
        "scene_hints: litterbox\n"
        "tags: 无尿, 频繁蹲猫砂盆\n"
        "---\n"
        "\n"
        "观察表现：频繁蹲猫砂盆、尿量明显减少。",
        encoding="utf-8",
    )
    (kb / "normal.md").write_text(
        "---\n"
        "title: 正常排尿\n"
        "scene_hints: litterbox\n"
        "tags: 尿团正常\n"
        "---\n"
        "\n"
        "观察表现：排尿频率和尿量均正常。",
        encoding="utf-8",
    )

    r = KnowledgeRetriever(knowledge_dir=tmp_path)
    r.reload()
    hits = r.retrieve(
        "它今天状态一般",
        scene_hint=SceneHint.LITTERBOX,
        model_tags=["排尿困难", "无尿"],
        limit=2,
    )
    assert hits
    assert hits[0].doc_id == "litterbox_urination"


def test_retriever_suppresses_negated_terms(tmp_path: Path) -> None:
    kb = tmp_path / "general"
    kb.mkdir()
    (kb / "vomit_risk.md").write_text(
        "---\n"
        "title: 反复呕吐\n"
        "scene_hints: general\n"
        "tags: 呕吐, 高风险\n"
        "---\n"
        "\n"
        "观察表现：反复呕吐需要警惕。",
        encoding="utf-8",
    )
    (kb / "low_energy.md").write_text(
        "---\n"
        "title: 精神萎靡\n"
        "scene_hints: general\n"
        "tags: 低能量, 不愿活动\n"
        "---\n"
        "\n"
        "观察表现：长时间趴卧不动、反应迟缓。",
        encoding="utf-8",
    )

    r = KnowledgeRetriever(knowledge_dir=tmp_path)
    r.reload()
    hits = r.retrieve("没有呕吐，就是有点没精神", scene_hint=SceneHint.GENERAL, limit=2)
    assert hits
    assert hits[0].doc_id == "general_low_energy"


def test_retriever_boosts_duration_terms_for_sustained_case(tmp_path: Path) -> None:
    kb = tmp_path / "appetite"
    kb.mkdir()
    (kb / "sustained_loss.md").write_text(
        "---\n"
        "title: 持续不进食\n"
        "scene_hints: appetite\n"
        "tags: 持续, 12小时, 拒食\n"
        "---\n"
        "\n"
        "观察表现：持续 12 小时不进食，建议尽快评估。",
        encoding="utf-8",
    )
    (kb / "short_term.md").write_text(
        "---\n"
        "title: 轻微挑食\n"
        "scene_hints: appetite\n"
        "tags: 轻微, 暂时\n"
        "---\n"
        "\n"
        "观察表现：短暂挑食，可先观察。",
        encoding="utf-8",
    )

    r = KnowledgeRetriever(knowledge_dir=tmp_path)
    r.reload()
    hits = r.retrieve("已经持续12小时不吃了", scene_hint=SceneHint.APPETITE, limit=2)
    assert hits
    assert hits[0].doc_id == "appetite_sustained_loss"


def test_retriever_prefers_mild_doc_for_low_intensity_query(tmp_path: Path) -> None:
    kb = tmp_path / "general"
    kb.mkdir()
    (kb / "high_risk.md").write_text(
        "---\n"
        "title: 严重精神萎靡\n"
        "scene_hints: general\n"
        "tags: 高风险, 尽快就医\n"
        "---\n"
        "\n"
        "观察表现：明显精神萎靡且状态快速恶化，属于高风险。",
        encoding="utf-8",
    )
    (kb / "mild.md").write_text(
        "---\n"
        "title: 轻微精神波动\n"
        "scene_hints: general\n"
        "tags: 轻微, 先观察\n"
        "---\n"
        "\n"
        "观察表现：偶尔没精神，可先观察并记录变化。",
        encoding="utf-8",
    )

    r = KnowledgeRetriever(knowledge_dir=tmp_path)
    r.reload()
    hits = r.retrieve("只是轻微没精神，偶尔这样", scene_hint=SceneHint.GENERAL, limit=2)
    assert hits
    assert hits[0].doc_id == "general_mild"


def test_retriever_filters_cross_scene_model_tags(tmp_path: Path) -> None:
    appetite = tmp_path / "appetite"
    appetite.mkdir()
    litterbox = tmp_path / "litterbox"
    litterbox.mkdir()
    (appetite / "loss.md").write_text(
        "---\n"
        "title: 食欲下降\n"
        "scene_hints: appetite\n"
        "tags: 食欲, 拒食, 进食减少\n"
        "---\n"
        "\n"
        "观察表现：食欲下降，不太愿意进食。",
        encoding="utf-8",
    )
    (litterbox / "urination.md").write_text(
        "---\n"
        "title: 排尿困难\n"
        "scene_hints: litterbox\n"
        "tags: 无尿, 频繁蹲猫砂盆, 高风险\n"
        "weight: 2.0\n"
        "---\n"
        "\n"
        "观察表现：频繁蹲猫砂盆且无尿，建议尽快就医。",
        encoding="utf-8",
    )

    r = KnowledgeRetriever(knowledge_dir=tmp_path)
    r.reload()
    hits = r.retrieve(
        "最近食欲很差，几乎不吃东西",
        scene_hint=SceneHint.APPETITE,
        model_tags=["排尿困难", "无尿"],
        limit=2,
    )
    assert hits
    assert hits[0].doc_id == "appetite_loss"
