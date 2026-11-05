use crate::Session;
use serde_json::json;

fn two_page_session() -> Session {
    let mut s = Session::new(wordcraft_doc::Document::new());
    for i in 0..120 {
        s.run("text.insert", &json!({"text": format!("Paragraph {i} with enough words to fill a line.")})).unwrap();
        s.run("text.newParagraph", &json!({})).unwrap();
    }
    s
}

#[test]
fn unchanged_pages_keep_their_content_key() {
    let mut s = two_page_session();
    let before: Vec<u64> = s.layout().pages.iter().map(|p| p.content_key()).collect();
    assert!(before.len() >= 2, "need two pages, got {}", before.len());
    // Typing at the end changes only the last page.
    s.run("text.insert", &json!({"text": "more"})).unwrap();
    let after: Vec<u64> = s.layout().pages.iter().map(|p| p.content_key()).collect();
    assert_eq!(before.first(), after.first(), "first page untouched");
    assert_ne!(before.last(), after.last(), "last page changed");
}

#[test]
fn view_key_and_render_options_follow_the_view() {
    let mut s = two_page_session();
    let k0 = s.page_view_key(false, None);
    assert!(!s.page_render_options(false).display.marks);
    s.view.marks = true;
    assert!(s.page_render_options(false).display.marks);
    let k1 = s.page_view_key(false, None);
    assert_ne!(k0, k1);
    s.view.dark_mode = true;
    assert!(s.dark_page());
    assert!(s.page_render_options(false).dark);
    assert_ne!(k1, s.page_view_key(false, None));
    assert!(!s.page_render_options(true).display.dim_header);
}
