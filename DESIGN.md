---
name: "AZclient Injector"
description: "The built sunset-colored operating interface for the local Windows injector."
colors:
  bg: "#17151e"
  surface: "#22202b"
  raised: "#2c2735"
  text: "#f7f0f6"
  muted: "#bdb1c4"
  dim: "#a99daf"
  pink: "#efadc8"
  lilac: "#c1aff5"
  peach: "#f4b48d"
  error: "#ff9ba9"
  ink: "#211b30"
  ink-muted: "#514362"
  line: "#494051"
  pink-hover: "#f7c3d8"
  disabled-bg: "#3c3546"
  disabled-text: "#b1a5bb"
typography:
  title:
    fontFamily: '"Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "28px"
    fontWeight: 600
    lineHeight: 1.25
    letterSpacing: "-0.025em"
  session-title:
    fontFamily: '"Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "32px"
    fontWeight: 600
    lineHeight: 1.2
    letterSpacing: "-0.03em"
  body:
    fontFamily: '"Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "14px"
    fontWeight: 400
    lineHeight: 1.55
  label:
    fontFamily: '"Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.55
  action-title:
    fontFamily: '"Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "16px"
    fontWeight: 600
    lineHeight: 1.55
  control:
    fontFamily: '"Segoe UI Variable Text", "Segoe UI", "Microsoft YaHei UI", "Microsoft YaHei", sans-serif'
    fontSize: "14px"
    fontWeight: 600
    lineHeight: 1.55
  log:
    fontFamily: 'Consolas, "Cascadia Mono", monospace'
    fontSize: "13px"
    fontWeight: 400
    lineHeight: 1.8
rounded:
  compact: "6px"
  control: "8px"
  mobile-welcome: "12px"
  session: "16px"
spacing:
  inline: "8px"
  small: "16px"
  row: "18px"
  section: "24px"
  compact-page: "28px"
  pane: "30px"
  wide-pane: "36px"
  page: "40px"
  wide-page: "56px"
components:
  button-primary:
    backgroundColor: "{colors.pink}"
    textColor: "{colors.ink}"
    typography: "{typography.control}"
    rounded: "{rounded.control}"
    padding: "0 22px"
  button-primary-hover:
    backgroundColor: "{colors.pink-hover}"
    textColor: "{colors.ink}"
  button-primary-disabled:
    backgroundColor: "{colors.disabled-bg}"
    textColor: "{colors.disabled-text}"
  button-primary-success:
    backgroundColor: "{colors.peach}"
    textColor: "{colors.ink}"
  button-text:
    backgroundColor: "transparent"
    textColor: "{colors.lilac}"
    typography: "{typography.label}"
    padding: "7px 0"
  button-icon:
    backgroundColor: "transparent"
    textColor: "{colors.muted}"
    rounded: "{rounded.compact}"
    width: "38px"
    height: "38px"
  field:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.body}"
    rounded: "{rounded.control}"
    padding: "10px 13px"
  navigation-item:
    backgroundColor: "transparent"
    textColor: "{colors.muted}"
    typography: "{typography.label}"
    rounded: "0"
    padding: "0 13px"
  navigation-item-active:
    textColor: "{colors.lilac}"
  auth-tab:
    backgroundColor: "transparent"
    textColor: "{colors.muted}"
    typography: "{typography.body}"
    padding: "0 0 8px"
  auth-tab-active:
    textColor: "{colors.pink}"
  session:
    backgroundColor: "{colors.lilac}"
    textColor: "{colors.ink}"
    rounded: "{rounded.session}"
  session-action:
    backgroundColor: "{colors.raised}"
    textColor: "{colors.text}"
    padding: "24px 30px"
  segmented:
    backgroundColor: "{colors.raised}"
    rounded: "{rounded.control}"
    padding: "4px"
  segmented-active:
    backgroundColor: "{colors.lilac}"
    textColor: "{colors.ink}"
    rounded: "5px"
    padding: "5px 17px"
  switch:
    width: "42px"
    height: "26px"
    rounded: "15px"
  log-output:
    backgroundColor: "{colors.surface}"
    textColor: "{colors.text}"
    typography: "{typography.log}"
    rounded: "{rounded.control}"
    padding: "22px"
---

# Design System: AZclient Injector

## Overview

**Creative North Star: "Sunset Session Roster"**

This system describes the built local injector web UI in `src-injector/resources/web/index.html`, `app.css`, and `app.js`. It does not govern the in-game GUI, HUD, rendering, or gameplay modules, which retain their separate existing style. The sunset palette is the durable brand commitment; the session-roster direction is the chosen expression for this operational interface.

The material is solid and quiet: a deep plum canvas, pale lilac working surfaces, pink actions, and peach completion feedback. Readable Chinese operating text, aligned information rows, and short state transitions carry the identity. The built injector has no gradients, backdrop blur, decorative glow, chart panels, or repeated dashboard-card grid. Its broad selected-session container and login welcome plane are intentional uses of a filled surface, not a general prohibition on containers.

This is a source-derived record of the completed UI implementation, checked against the available desktop and mobile browser captures. Those captures use fixture data and do not establish real-game injection or user acceptance. The existing `favicon.ico` is retained; no new raster assets are introduced. The surface brief owns page strategy and the chosen first-viewport composition.

**Key Characteristics:**

- Solid sunset color planes with dark readable text on pale accents.
- Compact operating typography with distinct game, page, action, and metadata roles.
- Aligned rows and native controls, with secondary details available on demand.
- Inline state feedback that stays with its target and respects reduced motion.

## Colors

The palette combines dark plum neutrals with soft pink, lilac, and peach; values in the frontmatter are normative and mirror the built stylesheet.

### Primary

- **Sunset Pink — `pink`:** login and injection actions, checkbox accent, text selection, and the inline progress fill. `pink-hover` is the filled action's hover treatment.
- **Pale Lilac — `lilac`:** selected-session and welcome planes, active app navigation, log-source selection, checked settings, and field focus. This is a working surface as well as a control accent.
- **Warm Peach — `peach`:** completed state, saved-session feedback, copied-content toast, and the general keyboard-focus ring. Copy remains necessary to distinguish these contexts.
- **Error Rose — `error`:** failure text and disconnected status; it accompanies an explicit error message rather than being the only signal.

### Neutral

- **Deep Plum — `bg`:** the app canvas.
- **Plum Surface — `surface`:** navigation, text inputs, and the log well.
- **Raised Plum — `raised`:** the session action band, segmented-control track, and inactive progress track.
- **Warm White — `text`:** primary text on dark surfaces; **Soft Lavender — `muted`** carries supporting text; **Dust Lavender — `dim`** carries timestamps and lower-priority metadata.
- **Plum Ink — `ink`** and **Muted Ink — `ink-muted`:** headings and supporting text on pale surfaces.
- **Plum Rule — `line`:** input boundaries, table separators, settings separators, and scrollbars. Disabled filled controls use their own `disabled-bg` and `disabled-text` values.

**The Surface Contrast Rule.** Pale lilac, pink, and peach filled elements use plum ink; dark planes use warm white or the lavender text levels.

## Typography

**Operating Font:** Segoe UI Variable Text, followed by Segoe UI, Microsoft YaHei UI, Microsoft YaHei, and sans-serif. The app bundles no font files and makes no font-network request. Actual glyph selection follows the installed system fonts.

**Log Font:** Consolas, followed by Cascadia Mono and monospace.

The typography is workmanlike and compact. Headings distinguish the task from supporting details without adding a decorative display family. The frontmatter records stable operating roles; responsive size changes below describe their actual use.

### Hierarchy

- **Session title:** the selected game's name; the desktop role contracts to (28px) at the medium breakpoint and (25px) on mobile.
- **Page title:** the section heading; it contracts to (25px) on mobile. Login uses a (25px) heading, then (24px) on mobile.
- **Action title:** the short next-step instruction above supporting copy.
- **Body:** ordinary text and input values. Labels, navigation, process metadata, status explanations, and timestamps use the smaller label role.
- **Log:** preserved whitespace and wrapped long lines, with generous line spacing for scanning.
- **Welcome heading:** a local login treatment using `clamp(32px, 3.2vw, 44px)`, then (32px) at medium widths and (25px) on mobile. It is not a global display-font rule.

PIDs, percentages, runtime, timestamps, and version numbers use tabular numerals. Process names and paths wrap when necessary; ordinary status and footnote text is bounded at (75ch) where specified. The wordmark uses a compact (24px) treatment, with a lighter “client” portion and a small pink dot; mobile reduces it to (22px).

**The Operating Text Rule.** Keep short interface labels in the shared UI family; reserve the monospace family for the log contents, not every technical value.

## Layout

The outer app fills at least the small viewport height and is capped at (1600px). Its header, horizontal navigation, working area, and footer form a vertical flex layout. The page container is centered and capped at (1060px), including padding. Default page padding is (40px 40px 48px). Sections use aligned rows rather than a dashboard grid: process rows, definition-list facts, and settings share readable horizontal tracks and restrained separators.

The selected-session container joins a pale identity region to a dark action region within one silhouette. The default identity padding is (30px 30px 24px), and its action region is (24px 30px). Status stays immediately below; recent activity is a disclosure. When multiple eligible targets exist, a native select appears inside the identity region. The login view uses two columns with `minmax(250px, .9fr)` and `minmax(360px, 1.1fr)`, a maximum width of (1000px), and a form column capped at (410px).

Responsive rules are cumulative:

| Condition | Built behavior |
| --- | --- |
| Width at least (1200px) | Header/navigation side padding becomes (56px); page top padding becomes (56px); session side padding becomes (36px). |
| Width at most (860px) | Header height becomes (66px); page padding becomes (30px 28px 32px); login columns tighten; the game title becomes (28px). |
| Width at most (620px) | Navigation wraps into two explicit rows; buttons in those rows have a minimum height of (42px). Page padding becomes (28px 22px 32px). Login stacks, its welcome plane has rounded corners on all sides, and its small bottom metadata disappears. |
| Width at most (620px), session | Identity and target controls stack; the state label sits under the game metadata; the primary action becomes full width beneath its explanation. |
| Width at most (620px), secondary pages | The process-type column disappears, facts stack label above value, log actions stack, the settings range fills its row, and footer runtime disappears. |
| Width at least (621px) and height at most (760px) | Login margins, fields, page gaps, session padding, status height, and footer padding compress so the ordinary desktop-sized app window stays usable. |

The minimum document width is (320px). Logs and activity have bounded scrolling regions; long paths and identifiers wrap rather than forcing the page wider. The status area reserves vertical room across states, while actual content is still allowed to determine height.

## Elevation & Depth

The built interface uses no box shadows. Depth comes from solid tonal differences, selected fills, and limited borders. The session's two tones are part of one container, while tables and settings remain on the page canvas. The disconnect overlay uses a near-opaque plum scrim, with no blur. A toast sits above the interface at the bottom center.

**The Tonal Depth Rule.** Use the existing canvas, surface, and raised tones to separate functional regions; the current injector's depth does not depend on shadows.

## Shapes

Controls use the modest control radius, with a tighter compact radius for select fields, copy buttons, and keyboard labels. The selected-session surface uses the larger session radius with overflow clipped, joining the pale upper region to the dark lower region. The desktop welcome plane rounds only its outer left corners; on mobile it becomes an independent rounded block.

Separators and input borders are (1px). Navigation state is a (2px) underline. The progress bar is (3px) tall with a small (2px) track radius; the saved-session countdown is (2px) tall. Round dots identify connection state, and the settings switch uses a pill track with a circular thumb. SVG icons use rounded strokes: ordinary icons are (18px) with a (1.65px) stroke, while the game illustration is (38px) with a (1.25px) stroke and reduces to (30px) on mobile.

## Components

### Buttons

Filled actions are soft-cornered, solid, and explicit. Their minimum height is (46px), with a (24px) content gap. Pink fills the normal primary action; hover lightens it, and active press moves down (1px) and scales to (.98). A completed session changes the action to peach. Disabled actions have their own subdued fill and text and a not-allowed cursor.

Text actions use lilac, turning pink and underlining on hover. They retain a minimum height of (34px). Icon buttons are (38px) square, use muted text at rest, and gain a raised fill and primary text on hover. The shared keyboard-focus treatment is a peach (2px) outline offset by (4px).

### Inputs and selection

Text inputs have a solid surface fill, a single line border, and a minimum height of (44px). Hover strengthens the border to muted text; focus uses lilac for both border and a (2px) outline offset by (1px). Labels remain visible above the inputs. Placeholder text uses the dim text level, while the caret is pink. Authentication failures are shown as an explicit form-status message; the source does not define a separate invalid-input skin.

The target selector retains native select behavior, a transparent fill on the pale plane, dark text, a compact radius, and a locally defined muted-purple border (`#75628e`). Its focus outline uses dark ink. It is disabled while an operation is busy. Checkbox and range controls remain native, accented in pink and lilac respectively.

### Navigation and segmented selection

App navigation is a solid surface strip. Items use muted text, a raised hover fill, and lilac active text with an underline; the active item also exposes `aria-current="page"`. Authentication tabs use pink for their active text and underline. The log-source selector is a compact raised track with a lilac selected segment and `aria-pressed` state. These are distinct existing selection patterns, not interchangeable decorative chips. The session state tag is plain text rather than a badge pill.

### Selected session and feedback

Waiting, ready, busy, success, and error states reuse the same selected-session structure. Waiting disables the action; ready identifies the target; busy disables conflicting controls and shows an accessible progress bar; success updates the text and peach feedback; failure shows its explanation and a diagnostic entry point below the same target. These meanings come from `app.js` and actual API state, not from the preview fixtures.

The progress width transitions over (360ms). A changed session/target uses a short masked reveal over (260ms), from an inset mask and (5px) vertical offset to its resting position. Ordinary panel changes use (180ms), moving (6px) while opacity rises from (.65) to (1). Both use `cubic-bezier(.16, 1, .3, 1)`. Buttons use (180ms) color changes and a (240ms) press transform; disclosure arrows rotate over (220ms). These transitions do not replay just because unchanged status is polled.

Both the operating-system reduced-motion preference and the app animation setting disable transitions and animations; active Web Animations are also cancelled. Motion changes presentation only, leaving status text and controls available.

### Rows, logs, and settings

Process tables use horizontal rules with normal-weight muted headers; facts align a (150px) label column with the value until the mobile breakpoint. Settings use the same row vocabulary with description text below the main label. The switch measures (42px × 26px), with an (18px) thumb moving (16px); checked state uses lilac and dark ink. Its focus ring remains on the visible track.

The log well is a solid surface with a control radius, pre-wrapped text, a minimum height of (260px), and a maximum height of (48vh). Mobile uses (16px) padding, a (300px) minimum, and a (55vh) maximum. Recent activity is a native details disclosure with time and message columns, capped at (230px) when open.

### Transient and disconnected states

Copy feedback uses a peach toast with dark text, visible for (3200ms), positioned (24px) from the bottom on desktop and (18px) on mobile. The disconnected overlay is a focused `role="alert"` surface. While it is visible, the underlying app is inert; reconnection removes the overlay and restores the prior focus when that control is still present, enabled, and visible. This is an interaction rule, not a new shadow or modal-card treatment.

## Do's and Don'ts

### Do:

- **Do** use plum ink on the pale accent planes and the existing light text levels on dark planes.
- **Do** preserve the game name, selected process, action, and result as distinct readable roles.
- **Do** keep keyboard focus, native selection behavior, explicit status text, and both reduced-motion controls intact.
- **Do** use aligned rows and disclosure for secondary information, following the built process, settings, and activity patterns.
- **Do** treat this document as the injector web system; inspect the separate incumbent design before changing in-game GUI or HUD surfaces.

### Don't:

- **Don't** use color alone to distinguish waiting, working, completion, or failure.
- **Don't** turn browser fixture values or screenshots into shipped runtime claims or evidence of successful injection.
- **Don't** add blur, gradients, decorative glow, or shadows as routine extensions of this injector's solid tonal material.
- **Don't** introduce remote fonts or raster dependencies into a UI that currently ships entirely from local resources.

No source defects are promoted into reusable rules by this document. Cross-platform font substitution and actual game-side behavior remain outside this source-and-capture verification.
