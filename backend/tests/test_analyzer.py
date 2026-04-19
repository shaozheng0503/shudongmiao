from app.domain.schemas import KnowledgeSnippet
from app.services.analyzer import CatStateAnalyzer


def test_analyzer_merges_cached_visual_hits_for_followup() -> None:
    analyzer = CatStateAnalyzer()
    analyzer._remember_visual_hits(
        "session-1",
        [
            KnowledgeSnippet(
                doc_id="visual_case",
                title="应激炸毛",
                category="stress",
                content="观察表现：炸毛。",
                score=1.2,
                source_type="visual",
                matched_image_ref="assets/stress.jpg",
            )
        ],
    )

    merged = analyzer._merge_with_visual_cache(
        "session-1",
        [
            KnowledgeSnippet(
                doc_id="text_case",
                title="排尿困难",
                category="litterbox",
                content="观察表现：频繁蹲盆。",
                score=2.0,
                source_type="text",
            )
        ],
        limit=5,
    )

    assert [item.doc_id for item in merged] == ["text_case", "visual_case"]
    assert merged[1].matched_image_ref == "assets/stress.jpg"


def test_analyzer_marks_hybrid_when_cache_and_text_hit_same_doc() -> None:
    analyzer = CatStateAnalyzer()
    analyzer._remember_visual_hits(
        "session-2",
        [
            KnowledgeSnippet(
                doc_id="shared_case",
                title="呼吸急促",
                category="breathing",
                content="观察表现：张口呼吸。",
                score=1.4,
                source_type="visual",
                matched_image_ref="assets/breath.jpg",
            )
        ],
    )

    merged = analyzer._merge_with_visual_cache(
        "session-2",
        [
            KnowledgeSnippet(
                doc_id="shared_case",
                title="呼吸急促",
                category="breathing",
                content="观察表现：张口呼吸。",
                score=2.6,
                source_type="text",
            )
        ],
        limit=5,
    )

    assert len(merged) == 1
    assert merged[0].source_type == "hybrid"
    assert merged[0].matched_image_ref == "assets/breath.jpg"
