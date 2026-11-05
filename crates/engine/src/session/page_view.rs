//! How the open view draws pages: shared by every front end (egui, Android) so a page raster
//! cache keys and renders the same way everywhere.
use super::Session;
use crate::cmd::view::ReadPageColor;
use std::hash::{Hash, Hasher};
use wordcraft_doc::Pos;

impl Session {
    /// Whether the pages are drawn dark: View › Switch Modes, or in Read Mode the Page color's
    /// Inverse (Switch Modes is ignored there).
    pub fn dark_page(&self) -> bool {
        let v = &self.view;
        if v.read_mode { v.read_page_color == ReadPageColor::Inverse } else { v.dark_mode }
    }

    /// Render options for this view's page rasters on screen: marks, markup, dark page, Read
    /// Mode paper, header/body dimming. Text darkening is off; the macOS front end turns it on.
    pub fn page_render_options(&self, dim_body: bool) -> wordcraft_render::RenderOptions {
        let mut opts = wordcraft_render::RenderOptions::default();
        opts.display.marks = self.view.marks;
        opts.display.always = self.prefs.marks;
        opts.display.placeholders = true;
        opts.display.markup = self.view.show_markup;
        // Ink Replay draws the ink itself, stroke by stroke, over pages without it.
        opts.display.hide_ink = self.view.hide_ink || self.view.draw.replay;
        opts.display.revisions = self.prefs.markup.clone();
        opts.dark = self.dark_page();
        opts.dark_paper = wordcraft_render::DARK_PAPER;
        // In Read Mode the Page color sets the paper.
        if self.view.read_mode {
            let color = self.view.read_page_color;
            opts.read_paper = Some(if color == ReadPageColor::Sepia { wordcraft_render::SEPIA_PAPER } else { wordcraft_doc::props::Rgb::WHITE });
        }
        opts.display.dim_header = !dim_body;
        opts.display.dim_body = dim_body;
        opts
    }

    /// Fingerprint of everything outside the layout that changes how pages render: the view's
    /// marks and markup, dark mode, the page colour and watermark, header/body dimming, the
    /// media, and (while a drag lasts) the object left out of the page.
    pub fn page_view_key(&self, dim_body: bool, hidden: Option<&Pos>) -> u64 {
        let mut h = std::collections::hash_map::DefaultHasher::new();
        let v = &self.view;
        (v.marks, self.prefs.marks, v.show_markup, v.dark_mode, wordcraft_render::DARK_PAPER, v.hide_ink, v.draw.replay).hash(&mut h);
        (v.read_mode, v.read_page_color).hash(&mut h);
        self.prefs.markup.hash(&mut h);
        dim_body.hash(&mut h);
        format!("{:?}{:?}{:?}", self.doc.settings.page_color, self.doc.settings.page_picture, self.doc.settings.watermark).hash(&mut h);
        self.doc.media.len().hash(&mut h);
        // A page drawn without a dragged object must not pass for the page with it: the drag starts
        // and ends change the key once each, so the page is rendered again then and only then.
        hidden.hash(&mut h);
        h.finish()
    }
}
