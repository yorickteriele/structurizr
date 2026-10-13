# Structurizr with PDF export (Docker)

This image bundles Structurizr (local, server and CLI) with Chromium, so it can export a
workspace to a **clickable PDF**: you can "zoom in" through your C4 model just like in the
diagram viewer.

## Build the image

```bash
docker build -t structurizr-pdf .
```

## Run Structurizr locally

```bash
docker run -it --rm -p 8080:8080 -v "$(pwd)/docker/workspace:/usr/local/structurizr" structurizr-pdf local
```

Then open http://localhost:8080. Edit `docker/workspace/workspace.dsl` (or mount your own folder).

Or with Docker Compose:

```bash
docker compose up --build
```

## Export to PDF

```bash
docker run --rm -v "$(pwd)/docker/workspace:/usr/local/structurizr" structurizr-pdf \
  export -w workspace.dsl -f pdf
```

Or with Docker Compose: `docker compose run --rm export`.

This writes `workspace.pdf` next to the workspace. Options:

| Option | Description |
|---|---|
| `-w workspace.dsl` | Workspace DSL/JSON file (or URL) |
| `-f pdf` | Export format |
| `-o <dir>` | Output directory (default: the workspace's directory) |
| `-mode light\|dark` | Colour scheme (default: `light`) |
| `-url <url>` | Export from a running Structurizr diagrams page instead of a workspace file |

## What's in the PDF

- **Contents page** linking to every view.
- **One page per view** (A4 landscape, vector graphics).
- **Click an element to zoom in**: software systems link to their system context/container
  views, containers link to their component views (same rules as double-clicking in the UI).
- **Back links** at the top of every page to the views that zoom into it, plus a link to the contents.
- A "Zoom in" list at the bottom of each page with all reachable child views.

Internal links work in any standard PDF viewer (browser, Acrobat, Preview, etc).
