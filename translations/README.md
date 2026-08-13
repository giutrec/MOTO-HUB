# MOTO-HUB translation catalogues

Each file in this directory is a complete Android string-resource catalogue
for one locale. The filename is deliberately explicit: `strings-it-IT.xml`,
`strings-pt-PT.xml`, and so on.

## Translator rules

1. Translate the text between the XML tags, not the `name` attribute.
2. Keep every placeholder exactly as written (`%1$s`, `%2$d`, etc.).
3. Keep technical names such as `GPS`, `GNSS`, `OSM`, and `Android Auto` unless
   the target language has an established equivalent.
4. Read the comment immediately above each string: it describes the screen,
   widget, or notification where the text is shown and any space constraints.
5. Escape XML characters (`&amp;`, `&lt;`, `&gt;`) when they occur in translated
   text. Do not add HTML or Markdown.

The English catalogue is the fallback and the source of truth for identifiers.
A translated catalogue may omit an identifier while it is being worked on;
Android will then fall back to English for that individual string.

Every catalogue holds the complete set of identifiers and every one of them is
translated: the `TODO` markers that used to stand in for untranslated entries
are gone. A catalogue may still omit an identifier while it is being worked on;
Android then falls back to English for that individual string.

The Android build maps the locale tags in these filenames to Android resource
qualifiers automatically (`it-IT` becomes `values-it`, `pt-PT` becomes
`values-pt-rPT`, `ko-KR` becomes `values-ko-rKR`, `fr-FR` becomes `values-fr`).
A new locale must be added in three places: this directory, the `localeDirectories`
map in `app/build.gradle.kts`, and `app/src/main/res/xml/locales_config.xml` —
plus an `AppLanguage` entry and a `language_*` string if it should appear in the
in-app language picker.

## Keeping the catalogue complete

Identifiers are derived from the English source text, so a new `motoHubText("…")`
or `WidgetDrawingContext.localized("…")` call silently falls back to its English
literal until the catalogue gains a matching entry. Nothing in the build fails
when that happens, which is how the catalogue fell 621 strings behind between
2026-08-08 and 2026-08-13. When adding UI text, add the catalogue entry in the
same change.
