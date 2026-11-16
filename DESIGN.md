# Handbook design

This document describes the implemented documentation website. It does not prescribe the future Android application's visual system.

## Surface and purpose

The handbook is a reading surface for a project owner, prospective implementers, and content authors. It provides a stable chapter order, source links, within-page headings on wide screens, local full-text chapter search, and browser printing. All content works offline. Markdown remains canonical.

The design uses a restrained academic reading layout: a pale green navigation column, warm paper, dark green ink, serif prose, and compact sans-serif reference tables. There are no marketing claims, imagery, animation, remote fonts, or decorative metric cards. The system serif is intentional for a portable documentation artifact with no external dependencies.

## Tokens and layout

CSS tokens are in `docs/assets/style.css`: paper `#faf9f5`, sidebar `#eeefe7`, ink `#20312b`, secondary text `#53645a`, accent `#245a44`, divider `#d6dcd1`, and selected surface `#d6e4d6`.

Body text uses Georgia/Times at 17px and 1.75 line height. Interface text uses the operating system sans-serif. The reading measure is at most 72ch. Headings use a restrained serif scale and no uppercase labels. A 250px sticky navigation becomes 210px below 850px; below 620px it becomes a compact top section with collapsible chapter navigation. Above 1450px an optional 220px heading outline appears.

Tables scroll within their own focusable regions. Code blocks retain literal spacing. Print removes site navigation and expands the reading area. Links, focus rings, text selection, and search states share the palette. Search is a progressive enhancement; without JavaScript, all prose and navigation remain accessible.

## Editing and checking

Change shared structure in `scripts/build_docs.py`, styling in `docs/assets/style.css`, search behavior in `docs/assets/search.js`, and source prose in `docs/markdown/`. Regenerate HTML after source or generator changes. Check wide and narrow layouts with long tables and code, search empty/no-result/result states, keyboard navigation, and print rendering after meaningful visual changes.
