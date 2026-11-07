//! WordCraft on Android. The Kotlin app (`app/` beside this crate) calls the three functions in
//! `exports.rs` through JNI. They land here, in safe Rust tested on the host: one `Session` for
//! the process, driven by command ids and JSON like the CLI, MCP and the control channel.
#![deny(clippy::unwrap_used, clippy::expect_used, clippy::panic, clippy::unimplemented, clippy::todo, clippy::unreachable)]
#![deny(unsafe_code)]

mod exports;

use serde_json::{Value, json};
use std::hash::{Hash, Hasher};
use std::sync::{Mutex, MutexGuard};
use wordcraft_engine::Session;

/// Largest page raster Kotlin may ask for (4096×4096): zooming in further scales the bitmap.
const MAX_PIXELS: f32 = 4096.0 * 4096.0;
/// Largest input-method delete, in characters.
const MAX_IME_DELETE: u64 = 4000;
/// Formats a save keeps everything in, so the document counts as saved (as `file.save`).
const KEEPS_EVERYTHING: [&str; 10] = ["docx", "docm", "dotx", "dotm", "odt", "ott", "fodt", "rtf", "json", "xml"];

struct App {
    session: Session,
    /// `Session::clipboard_rev` last handed to Android, so a copy is reported once.
    clip_sent: u64,
    keys: PageKeys,
}

/// The content keys of one layout's pages. Hashing a page formats every border and fill, which
/// costs tens of milliseconds on a long table, and `view` is asked for after every edit, caret
/// move and recovery tick. A layout never changes once made (the session hands out the same one
/// until an edit), so the keys are taken once per layout, as ui-egui's `PageKeys` does.
#[derive(Default)]
struct PageKeys {
    /// The layout `keys` are for; held so its address can't be reused by another one.
    layout: Option<std::sync::Arc<wordcraft_engine::layout::DocLayout>>,
    keys: Vec<u64>,
}

impl PageKeys {
    fn of(&mut self, layout: &std::sync::Arc<wordcraft_engine::layout::DocLayout>) -> &[u64] {
        if !self.layout.as_ref().is_some_and(|l| std::sync::Arc::ptr_eq(l, layout)) {
            self.keys = layout.pages.iter().map(wordcraft_engine::layout::Page::content_key).collect();
            self.layout = Some(layout.clone());
        }
        &self.keys
    }
}

static APP: Mutex<Option<App>> = Mutex::new(None);

/// The session lock. A panic while it was held poisons it; `exports`' `with_env` catches the
/// panic and the session carries on. `Session::run` restores the document after a panic inside
/// a command, but a panic in `view()` or layout is outside `run`: the session may be left
/// mid-update there, though it is still usable.
fn lock() -> MutexGuard<'static, Option<App>> {
    APP.lock().unwrap_or_else(std::sync::PoisonError::into_inner)
}

/// One JSON call from Kotlin: `{"ok": …}` or `{"error": "…"}`.
pub fn call(method: &str, params: &str) -> String {
    let params: Value = match serde_json::from_str(params) {
        Ok(v) => v,
        Err(e) => return json!({"error": format!("bad JSON: {e}")}).to_string(),
    };
    match dispatch(&mut lock(), method, &params) {
        Ok(v) => json!({"ok": v}).to_string(),
        Err(e) => json!({"error": e}).to_string(),
    }
}

fn dispatch(slot: &mut Option<App>, method: &str, p: &Value) -> Result<Value, String> {
    if method == "init" {
        return init(slot, p);
    }
    let app = slot.as_mut().ok_or("call init first")?;
    match method {
        "run" => run(app, p),
        "view" => Ok(view(app)),
        "commands" => Ok(commands(&app.session)),
        "shortcut" => shortcut(app, p),
        "paste" => paste(app, p),
        "ime.deleteSurrounding" => Ok(ime_delete(app, p)),
        "markSaved" => mark_saved(app, p),
        "recovery.tick" => {
            app.session.recovery_tick();
            Ok(json!({"busy": app.session.recovery_busy(), "error": app.session.recovery_error()}))
        }
        "recovery.list" => Ok(recovery_list(&app.session)),
        _ => Err(format!("unknown method `{method}`")),
    }
}

fn init(slot: &mut Option<App>, p: &Value) -> Result<Value, String> {
    let fresh = slot.is_none() || p.get("reset").and_then(Value::as_bool) == Some(true);
    if fresh {
        let region = wordcraft_engine::Region::from_tag(p.get("locale").and_then(Value::as_str).unwrap_or("en-US"));
        let doc = if p.get("sample").and_then(Value::as_bool) == Some(true) {
            wordcraft_engine::sample::sample_document()
        } else {
            wordcraft_engine::blank_document(&region)
        };
        let mut session = Session::new(doc);
        session.system_region = region;
        // The app's Dark mode setting; View > Switch Modes changes the page after this, for the session.
        session.view.dark_mode = p.get("dark").and_then(Value::as_bool) == Some(true);
        if let Some(dir) = p.get("filesDir").and_then(Value::as_str) {
            // Kotlin asks for a tick every few seconds, so the wakeup has nothing to do.
            if let Err(e) = session.enable_recovery(std::path::Path::new(dir).join("recovery"), std::sync::Arc::new(|| {})) {
                session.status = format!("Recovery is off: {e}");
            }
        }
        *slot = Some(App { session, clip_sent: 0, keys: PageKeys::default() });
    }
    let app = slot.as_mut().ok_or("init failed")?;
    // `fresh`: this call made the session. A recreated activity finds the old one, whose documents are open and not to be offered again.
    Ok(json!({"view": view(app), "recovered": recovery_list(&app.session), "fresh": fresh}))
}

/// Saving and exporting write through Android's file picker, so the app does them: these come
/// back as requests instead of writing a path on the device.
fn app_request(id: &str) -> Option<&'static str> {
    match id {
        // A new document would sit beside the one on screen, out of reach: the app closes the old one first.
        "file.new" => Some("new"),
        "file.save" => Some("save"),
        "file.saveAs" => Some("saveAs"),
        "file.exportPdf" => Some("exportPdf"),
        "file.exportPng" => Some("exportPng"),
        _ => None,
    }
}

fn run(app: &mut App, p: &Value) -> Result<Value, String> {
    let id = p.get("id").and_then(Value::as_str).ok_or("`id` (a command id) required")?;
    let params = p.get("params").cloned().unwrap_or_else(|| json!({}));
    let ime = p.get("ime").and_then(Value::as_bool) == Some(true);
    Ok(run_id(app, id, &params, ime))
}

fn run_id(app: &mut App, id: &str, params: &Value, ime: bool) -> Value {
    if let Some(open) = app_request(id) {
        app.session.ui_requests.push(json!({"open": open}));
        return edit_reply(app, Ok(Value::Null));
    }
    let result = if ime { app.session.run_ime(id, params) } else { app.session.run(id, params) };
    edit_reply(app, result.map_err(|e| e.to_string()))
}

/// The reply to every edit: what happened, the new view, requests for the app, and a copy for
/// the system clipboard when there is a new one.
fn edit_reply(app: &mut App, result: Result<Value, String>) -> Value {
    let requests = std::mem::take(&mut app.session.ui_requests);
    let clipboard = (app.session.clipboard_rev != app.clip_sent).then(|| {
        app.clip_sent = app.session.clipboard_rev;
        json!({"text": app.session.clipboard_text, "html": app.session.clipboard_html})
    });
    let (result, error) = match result {
        Ok(v) => (v, Value::Null),
        Err(e) => (Value::Null, Value::String(e)),
    };
    json!({"result": result, "error": error, "view": view(app), "uiRequests": requests, "clipboard": clipboard})
}

fn view(app: &mut App) -> Value {
    let App { session: s, keys, .. } = app;
    let layout = s.layout();
    let view_key = s.page_view_key(false, None);
    let content = keys.of(&layout);
    let pages: Vec<Value> = layout
        .pages
        .iter()
        .zip(content)
        .map(|(pg, content)| {
            let mut h = std::collections::hash_map::DefaultHasher::new();
            (view_key, content).hash(&mut h);
            json!({"w": pg.w, "h": pg.h, "key": format!("{:016x}", h.finish())})
        })
        .collect();
    let caret =
        layout.caret_on(&s.sel.focus, s.page_hint).map(|c| json!({"page": c.page, "x": c.x, "top": c.top, "height": c.height, "width": c.width}));
    let (a, b) = s.sel.ordered();
    let selection: Vec<Value> = if a == b {
        Vec::new()
    } else {
        wordcraft_engine::cmd::table_mouse::selection_rects(&layout, &s.doc, &a, &b, s.page_hint)
            .iter()
            .map(|(page, r)| json!({"page": page, "x": r.x, "y": r.y, "w": r.w, "h": r.h}))
            .collect()
    };
    let ime = s.doc.ime_surrounding(&s.sel.focus, &s.sel.anchor, wordcraft_doc::ime_text::MAX_IME_TEXT_BYTES).and_then(|w| {
        let utf16 = |byte: usize| w.text.get(..byte).map(|t| t.encode_utf16().count());
        Some(json!({"text": w.text, "cursor": utf16(w.cursor)?, "anchor": utf16(w.anchor)?,
                "para": format!("{:?}{:?}", s.sel.focus.story, s.sel.focus.path)}))
    });
    // Called directly, not through `run`: a dispatch resets IME bookkeeping, and the view is read after every keystroke.
    let format = wordcraft_engine::cmd::format::state(s);
    let title = s.documents().into_iter().find(|d| d.active).map(|d| d.title).unwrap_or_default();
    json!({
        "docId": s.document_id(), "rev": s.rev(), "dirty": s.dirty, "title": title,
        "canUndo": s.can_undo(), "canRedo": s.can_redo(), "status": s.status,
        "dark": s.dark_page(), "pages": pages, "caret": caret, "selection": selection, "format": format, "ime": ime,
    })
}

fn commands(s: &Session) -> Value {
    let list: Vec<Value> = s
        .registry
        .all()
        .iter()
        .map(|c| json!({"id": c.id, "label": c.label, "location": c.location, "shortcut": c.shortcut, "params": c.params, "enabled": s.why_disabled(c.id).is_none()}))
        .collect();
    let catalog: Vec<Value> = wordcraft_engine::catalog::entries()
        .into_iter()
        .filter(|(_, _, _, id)| s.registry.get(id).is_some())
        .map(|(tab, group, label, id)| json!({"tab": tab, "group": group, "label": label, "id": id}))
        .collect();
    json!({"commands": list, "catalog": catalog})
}

fn shortcut(app: &mut App, p: &Value) -> Result<Value, String> {
    let key = p.get("key").and_then(Value::as_str).ok_or("`key` required")?;
    let Some(id) = app.session.keymap.resolve(&app.session.registry, key).map(|c| c.id) else {
        return Ok(json!({"unbound": true}));
    };
    let mut reply = run_id(app, id, &json!({}), false);
    if let Some(o) = reply.as_object_mut() {
        o.insert("id".into(), json!(id));
    }
    Ok(reply)
}

fn paste(app: &mut App, p: &Value) -> Result<Value, String> {
    let text = p.get("text").and_then(Value::as_str).unwrap_or("");
    // The system clipboard still holds what WordCraft copied: paste the copy itself, formatting
    // and all. Anything else is text from another app.
    let params = if text.is_empty() || text == app.session.clipboard_text { json!({}) } else { json!({"text": text}) };
    Ok(run_id(app, "edit.paste", &params, false))
}

fn ime_delete(app: &mut App, p: &Value) -> Value {
    let count = |k: &str| p.get(k).and_then(Value::as_u64).unwrap_or(0).min(MAX_IME_DELETE) as usize;
    let (before, after) = (count("before"), count("after"));
    let s = &mut app.session;
    let (focus, anchor) = (s.sel.focus.clone(), s.sel.anchor.clone());
    let Some(del) = s.doc.ime_delete_surrounding(&focus, &anchor, before, after, wordcraft_doc::ime_text::DeleteUnit::Chars) else {
        return edit_reply(app, Err("nothing to delete there".into()));
    };
    // The range after the selection first, so the offsets before it still hold.
    let mut result = Ok(Value::Null);
    for range in [del.after, del.before].into_iter().flatten() {
        let at = |off: usize| wordcraft_engine::cmd::pos_json(&wordcraft_doc::Pos { off, ..focus.clone() });
        result = s
            .run_ime("select.range", &json!({"anchor": at(range.start), "focus": at(range.end)}))
            .and_then(|_| s.run_ime("text.delete", &json!({})))
            .map_err(|e| e.to_string());
        if result.is_err() {
            break;
        }
    }
    edit_reply(app, result)
}

fn ext_of(name: &str) -> String {
    name.rsplit_once('.').map(|(_, e)| e.to_ascii_lowercase()).unwrap_or_default()
}

fn mark_saved(app: &mut App, p: &Value) -> Result<Value, String> {
    let name = p.get("name").and_then(Value::as_str).filter(|n| !n.is_empty()).ok_or("`name` required")?;
    let s = &mut app.session;
    s.stamp_save();
    if KEEPS_EVERYTHING.contains(&ext_of(name).as_str()) {
        s.path = Some(name.into());
        s.dirty = false;
    }
    Ok(view(app))
}

fn recovery_list(s: &Session) -> Value {
    Value::Array(s.recovery_documents().iter().map(|r| json!({"id": r.id, "name": r.name, "savedAt": r.saved_at})).collect())
}

/// Page `index` at `scale` pixels per point: width and height as little-endian u32, then
/// premultiplied RGBA8 rows. Empty for a page that isn't there or a scale out of range.
pub fn render(index: i32, scale: f32) -> Vec<u8> {
    let Ok(index) = usize::try_from(index) else { return Vec::new() };
    if !(scale.is_finite() && scale > 0.0) {
        return Vec::new();
    }
    let mut guard = lock();
    let Some(app) = guard.as_mut() else { return Vec::new() };
    let layout = app.session.layout();
    let Some(page) = layout.pages.get(index) else { return Vec::new() };
    if page.w * scale * page.h.min(1e6) * scale > MAX_PIXELS {
        return Vec::new();
    }
    let opts = app.session.page_render_options(false);
    let img = wordcraft_engine::render::render_page(&app.session.doc, page, scale, &opts);
    let mut out = Vec::with_capacity(8 + img.pixels.len());
    out.extend_from_slice(&img.width.to_le_bytes());
    out.extend_from_slice(&img.height.to_le_bytes());
    out.extend_from_slice(&img.pixels);
    out
}

/// The document in `name`'s format (by extension), for the app to write: `0` then the bytes, or
/// `1` then the error. The save stamps go into these bytes only: the document counts as saved
/// once the app has written them and calls `markSaved`.
pub fn save(name: &str) -> Vec<u8> {
    let result = (|| -> Result<Vec<u8>, String> {
        let mut guard = lock();
        let app = guard.as_mut().ok_or("call init first")?;
        let s = &mut app.session;
        let before =
            (s.doc.settings.zoom, s.doc.core.last_modified_by.clone(), s.doc.core.created.clone(), s.doc.core.modified.clone(), s.doc.core.revision);
        s.stamp_save();
        let password = s.password.as_ref().map(wordcraft_engine::Password::as_str);
        let opts = wordcraft_engine::cmd::file::export_options(s, &json!({}));
        let bytes = wordcraft_engine::io::save_bytes_with(name, &s.doc, password, &opts);
        (s.doc.settings.zoom, s.doc.core.last_modified_by, s.doc.core.created, s.doc.core.modified, s.doc.core.revision) = before;
        bytes
    })();
    match result {
        Ok(bytes) => [&[0u8][..], &bytes].concat(),
        Err(e) => [&[1u8][..], e.as_bytes()].concat(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::Value;

    static TEST_LOCK: std::sync::Mutex<()> = std::sync::Mutex::new(());

    fn ok(method: &str, params: Value) -> Value {
        let out: Value = serde_json::from_str(&call(method, &params.to_string())).unwrap();
        assert!(out.get("error").is_none(), "{method} failed: {out}");
        out["ok"].clone()
    }

    #[test]
    fn edit_render_save_reopen() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let v = ok("init", json!({"reset": true, "locale": "en-US"}));
        assert!(!v["view"]["pages"].as_array().unwrap().is_empty());
        let r = ok("run", json!({"id": "text.insert", "params": {"text": "Hello tablet"}}));
        assert_eq!(r["error"], Value::Null);
        assert_eq!(r["view"]["dirty"], true);
        assert!(r["view"]["ime"]["text"].as_str().unwrap().contains("Hello tablet"));
        assert_eq!(r["view"]["ime"]["cursor"], 12);
        assert!(r["view"]["caret"]["height"].as_f64().unwrap() > 0.0);
        assert_eq!(r["view"]["pages"][0]["key"].as_str().unwrap().len(), 16);

        let img = render(0, 1.0);
        let w = u32::from_le_bytes(img[0..4].try_into().unwrap()) as usize;
        let h = u32::from_le_bytes(img[4..8].try_into().unwrap()) as usize;
        assert!(w > 500 && h > 700, "{w}x{h}");
        assert_eq!(img.len(), 8 + w * h * 4);

        let saved = save("Tablet.docx");
        assert_eq!(saved.first(), Some(&0));
        assert_eq!(ok("view", json!({}))["dirty"], true, "not saved until markSaved");
        assert_eq!(ok("markSaved", json!({"name": "Tablet.docx"}))["dirty"], false);

        // Selecting and Mod+B makes it bold; the format state says so.
        ok("run", json!({"id": "select.all"}));
        let b = ok("shortcut", json!({"key": "Mod+B"}));
        assert_eq!(b["id"], "format.bold");
        assert_eq!(b["view"]["format"]["bold"], true);

        // Copy reports the clipboard once.
        let c = ok("run", json!({"id": "edit.copy"}));
        assert_eq!(c["clipboard"]["text"].as_str().unwrap().trim_end(), "Hello tablet");
        assert_eq!(ok("run", json!({"id": "caret.docEnd"}))["clipboard"], Value::Null);

        // Copying the same text again is a new copy: Android may hold something else by now.
        ok("run", json!({"id": "select.all"}));
        let again = ok("run", json!({"id": "edit.copy"}));
        assert_eq!(again["clipboard"]["text"], c["clipboard"]["text"]);
        assert_eq!(ok("run", json!({"id": "caret.docEnd"}))["clipboard"], Value::Null);

        // The input method deletes "tablet" before the caret.
        let d = ok("ime.deleteSurrounding", json!({"before": 6, "after": 0}));
        assert_eq!(d["error"], Value::Null);
        assert!(d["view"]["ime"]["text"].as_str().unwrap().starts_with("Hello "));
        assert!(!d["view"]["ime"]["text"].as_str().unwrap().contains("tablet"));

        // Save is the app's job: it comes back as a UI request.
        let s = ok("run", json!({"id": "file.save"}));
        assert_eq!(s["uiRequests"][0]["open"], "save");

        // Reopen the saved bytes through file.open's data path.
        let b64 = wordcraft_engine::cmd::insert::base64_encode(saved.get(1..).unwrap());
        let o = ok("run", json!({"id": "file.open", "params": {"path": "Tablet.docx", "data": b64}}));
        assert_eq!(o["error"], Value::Null);
        assert!(o["view"]["ime"]["text"].as_str().unwrap().contains("Hello tablet"));
    }

    /// About a hundred pages of bordered tables, opened through `file.open` as HTML.
    fn open_table_document() -> usize {
        let mut html = String::from("<html><body>");
        for t in 0..60 {
            html.push_str(&format!("<h2>Table {t}</h2><table border=\"1\">"));
            for r in 0..40 {
                html.push_str(&format!("<tr><td>row {r}</td><td>alpha</td><td>beta</td><td>{t}</td></tr>"));
            }
            html.push_str("</table>");
        }
        html.push_str("</body></html>");
        let data = wordcraft_engine::cmd::insert::base64_encode(html.as_bytes());
        let o = ok("run", json!({"id": "file.open", "params": {"path": "tables.html", "data": data}}));
        assert_eq!(o["error"], Value::Null);
        o["view"]["pages"].as_array().map_or(0, Vec::len)
    }

    /// F6: one `view` reply on a long table document must stay cheap, since one follows every keystroke.
    /// Run `cargo test --release -p wordcraft-android view_cost -- --nocapture` for the numbers.
    #[test]
    fn view_cost_on_a_long_table_document() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        ok("init", json!({"reset": true, "locale": "en-US"}));
        let pages = open_table_document();
        assert!(pages >= 50, "{pages} pages");
        ok("view", json!({}));
        let n = 10;
        let t = std::time::Instant::now();
        for _ in 0..n {
            ok("view", json!({}));
        }
        let each = t.elapsed().as_secs_f64() * 1000.0 / f64::from(n);
        let typed = std::time::Instant::now();
        for _ in 0..n {
            ok("run", json!({"id": "text.insert", "params": {"text": "x"}}));
        }
        let each_typed = typed.elapsed().as_secs_f64() * 1000.0 / f64::from(n);
        eprintln!("view_cost: {pages} pages, view {each:.2} ms, keystroke {each_typed:.2} ms");
        // The cache must not hide an edit: the page typed on gets a new key, a view alone changes none.
        let keys = |v: &Value| v["pages"].as_array().unwrap().iter().map(|p| p["key"].as_str().unwrap().to_owned()).collect::<Vec<_>>();
        let before = keys(&ok("view", json!({})));
        assert_eq!(before, keys(&ok("view", json!({}))));
        ok("run", json!({"id": "caret.docStart"}));
        let after = keys(&ok("run", json!({"id": "text.insert", "params": {"text": "changed"}}))["view"]);
        assert_ne!(before.first(), after.first(), "the edited page keeps its key");
        if !cfg!(debug_assertions) {
            assert!(each < 5.0, "view takes {each:.2} ms");
        }
    }

    #[test]
    fn file_new_is_a_request_and_changes_nothing() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let v = ok("init", json!({"reset": true, "locale": "en-US"}));
        let id = v["view"]["docId"].clone();
        for r in [ok("run", json!({"id": "file.new"})), ok("shortcut", json!({"key": "Mod+N"}))] {
            assert_eq!(r["uiRequests"][0]["open"], "new", "{r}");
            assert_eq!(r["view"]["docId"], id, "no document was added");
        }
        assert_eq!(ok("view", json!({}))["docId"], id);
    }

    #[test]
    fn init_says_whether_it_made_the_session() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        assert_eq!(ok("init", json!({"reset": true}))["fresh"], true);
        ok("run", json!({"id": "text.insert", "params": {"text": "kept"}}));
        let again = ok("init", json!({"locale": "en-US"}));
        assert_eq!(again["fresh"], false, "a recreated activity finds the session");
        assert_eq!(again["view"]["dirty"], true, "and its document");
    }

    #[test]
    fn dark_mode_follows_the_setting_and_changes_only_the_page() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        let v = ok("init", json!({"reset": true, "dark": true}))["view"].clone();
        assert_eq!(v["dark"], true);
        let keys = |v: &Value| v["pages"].as_array().unwrap().iter().map(|p| p["key"].clone()).collect::<Vec<_>>();
        let r = ok("run", json!({"id": "view.darkMode", "params": {"value": false}}));
        assert_eq!(r["error"], Value::Null);
        assert_eq!(r["view"]["dark"], false);
        assert_ne!(keys(&v), keys(&r["view"]), "the pages are drawn again");
        assert_eq!(r["view"]["dirty"], false, "a view setting is not an edit");
        assert_eq!(ok("init", json!({"reset": true}))["view"]["dark"], false, "light unless asked");
        assert_eq!(ok("init", json!({"dark": true}))["view"]["dark"], false, "a recreated activity keeps the session");
    }

    #[test]
    fn hostile_input_never_panics() {
        let _g = TEST_LOCK.lock().unwrap_or_else(|p| p.into_inner());
        ok("init", json!({"reset": true}));
        let methods = [
            "init",
            "run",
            "view",
            "commands",
            "shortcut",
            "paste",
            "ime.deleteSurrounding",
            "markSaved",
            "recovery.tick",
            "recovery.list",
            "nope",
            "",
        ];
        let params = [
            "",
            "null",
            "[]",
            "{}",
            "7",
            r#"{"id":5}"#,
            r#"{"id":"text.insert","params":7}"#,
            r#"{"id":"caret.set","params":{"page":-1,"x":1e30,"y":"a"}}"#,
            r#"{"before":-1,"after":1e30}"#,
            r#"{"before":18446744073709551615}"#,
            r#"{"name":""}"#,
            r#"{"name":"."}"#,
            r#"{"key":"Mod+"}"#,
            r#"{"key":""}"#,
            r#"{"text":"\u0000\ud800"}"#,
        ];
        for m in methods {
            for p in params {
                let out: Value = serde_json::from_str(&call(m, p)).unwrap();
                assert!(out.get("ok").is_some() || out.get("error").is_some(), "{m} {p}: {out}");
            }
        }
        for (page, scale) in [(-1, 1.0), (0, 0.0), (0, -1.0), (0, f32::NAN), (0, f32::INFINITY), (0, 1e9), (i32::MAX, 1.0)] {
            let _ = render(page, scale);
        }
        assert_eq!(render(0, f32::NAN), Vec::<u8>::new());
        for name in ["", ".", "x", "x.nope", "../../etc/passwd.docx"] {
            let out = save(name);
            assert!(matches!(out.first(), Some(0) | Some(1)));
        }
    }
}
