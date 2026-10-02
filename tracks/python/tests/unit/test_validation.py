"""M1: every input rule from spec/requirements.md (FR-1, FR-4, FR-7)."""

import pytest

from app import validation as v
from app.errors import ApiError


def rejects(fn, *args):
    with pytest.raises(ApiError) as exc:
        fn(*args)
    assert exc.value.status == 400
    return exc.value.detail


def test_email_is_trimmed_and_lower_cased():
    assert v.email("  Asha@Example.COM ") == "asha@example.com"


@pytest.mark.parametrize("bad", [None, 42, "", "no-at-sign", "a@b", "a b@c.dev", "@x.dev", "a@" + "x" * 250 + ".dev"])
def test_email_rejects(bad):
    rejects(v.email, bad)


def test_password_limits_are_characters_and_bytes():
    assert v.password("12345678") == "12345678"
    assert v.password("x" * 72) == "x" * 72
    rejects(v.password, "1234567")
    rejects(v.password, "x" * 73)
    rejects(v.password, "é" * 37)  # 37 characters but 74 bytes: bcrypt only reads 72 bytes
    rejects(v.password, None)


def test_display_name():
    assert v.display_name("  Asha  ") == "Asha"
    rejects(v.display_name, "   ")
    rejects(v.display_name, "x" * 51)


def test_role():
    assert v.role("AUTHOR") == "AUTHOR"
    rejects(v.role, "author")
    rejects(v.role, "ROOT")


def test_title_and_body():
    assert v.title("  Hello  ") == "Hello"
    rejects(v.title, "")
    rejects(v.title, "x" * 201)
    assert v.body("  keep *markdown* as is ") == "  keep *markdown* as is "
    rejects(v.body, "   ")
    rejects(v.body, "x" * 50_001)
    rejects(v.body, 7)


def test_tags_are_normalised_deduplicated_and_sorted():
    assert v.tags(None) == []
    assert v.tags([" Java", "spring-boot", "java"]) == ["java", "spring-boot"]


@pytest.mark.parametrize(
    "bad", ["java", [1], ["a", "b", "c", "d", "e", "f"], ["two words"], ["-java"], ["a--b"], ["x" * 31]]
)
def test_tags_reject(bad):
    rejects(v.tags, bad)


def test_status_and_version():
    assert v.status(None) == "draft"
    assert v.status("published") == "published"
    rejects(v.status, "archived")
    rejects(v.status, None, None)  # in a PATCH, an explicit null is not "keep the old value"
    assert v.version(3) == 3
    rejects(v.version, None)
    rejects(v.version, 0)
    rejects(v.version, "1")
    rejects(v.version, True)  # True is an int in Python


def test_comment_body_and_parent():
    assert v.comment_body("  nice post  ") == "nice post"
    rejects(v.comment_body, " ")
    rejects(v.comment_body, "x" * 2001)
    assert v.parent_id(None) is None
    assert v.parent_id(5) == 5
    rejects(v.parent_id, 0)
    rejects(v.parent_id, "5")
    rejects(v.parent_id, False)


def test_feed_parameters():
    assert v.limit(None) == 10
    assert v.limit("50") == 50
    for bad in ["0", "51", "-1", "ten", "1.5", "²", "\u0661\u0660"]:
        rejects(v.limit, bad)
    assert v.tag_filter(None) is None
    assert v.tag_filter("") is None
    assert v.tag_filter("Java") == "java"
    rejects(v.tag_filter, "not a tag")
    assert v.search_query("  ") is None
    assert v.search_query(" docker ") == "docker"
    rejects(v.search_query, "x" * 101)


def test_admin_parameters():
    assert v.comment_status(None) == "held"
    assert v.comment_status("rejected") == "rejected"
    rejects(v.comment_status, "deleted")
    assert v.decision("approve") == "approve"
    rejects(v.decision, "maybe")


def test_body_must_be_an_object():
    assert v.require_object({"a": 1}) == {"a": 1}
    for bad in [None, [], "text", 3]:
        rejects(v.require_object, bad)


def test_path_ids_that_cannot_exist_are_404():
    assert v.path_id("42", "comment") == 42
    for bad in ["abc", "-1", "1.0", "9" * 19, "²"]:
        with pytest.raises(ApiError) as exc:
            v.path_id(bad, "comment")
        assert exc.value.status == 404
