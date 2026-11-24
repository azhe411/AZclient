# AZclient injector

<!-- impeccable:product-schema 1 -->

## Platform

web

## Users

The existing owner uses the Windows executable to connect AZclient to a running Minecraft Java process. The owner confirmed on 2026-10-18 that everyday launch and injection take priority over persistent diagnostic output.

## Product Purpose

Let the user sign in, identify the intended running game, start injection, and understand the actual result. A successful screen makes the next action and current process unmistakable.

## Operating Context

AZclient.exe starts a local Java HTTP service and opens its bundled HTML/CSS/JavaScript in a browser app window. The game is started separately. Users may need to register with an invitation, copy a machine identifier for account binding, inspect Java processes, or recover using logs and diagnostics.

## Capabilities and Constraints

Existing local APIs provide authentication, registration, remembered sessions, process scanning, injection progress, logs, diagnostics, animation preference, and log-length preference. Preserve their contracts and real error states. The UI must work from packaged local assets without a CDN or a web build framework. The redesign concerns the injector, not the in-game GUI or gameplay modules. Browser-rendered fixtures are not proof of actual game injection.

## Brand Commitments

Keep the AZclient name and existing sunset colors: dark plum neutrals with pink, lilac, and peach. The user explicitly requests a full interface reconstruction, retaining only the colors, guided by taste-skill and impeccable. No requirement to retain the incumbent layout, typography, cards, glow, or animations.

## Evidence on Hand

The current implementation is in src-injector/resources/web; WebUi.java supplies the local APIs. Existing probe fixtures and build.ps1 exercise packaging and compatibility. There are no supplied product photographs or new game screenshots for this injector redesign. Demonstration process/account data must be marked as fixture data in preview materials and never shipped as live status.

## Product Principles

- Put the running game and injection action before diagnostics.
- Show actual state; distinguish discovery, working, completion, and failure.
- Keep account and recovery tools available without crowding the launch flow.
- Make controls readable and usable with keyboard input and reduced motion.
