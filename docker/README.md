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

### Using your own workspace folder

Copy `.env.example` to `.env` and set the folder that contains your `workspace.dsl`:

```
STRUCTURIZR_WORKSPACE=D:/projects/my-architecture
STRUCTURIZR_PORT=8080
```

Docker Compose reads `.env` automatically (`.env` is git-ignored).

## Export to PDF

### From the browser

Open a diagram, click the **export** button (toolbar) and choose **Export as PDF**. Select the views
to include (ctrl/shift-click), or select fewer than two to include all views. The PDF is downloaded straight away.

### From the command line

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
- **Breadcrumbs** at the top of every page (Contents › Landscape › Context › Containers), each one clickable.

Internal links work in any standard PDF viewer (browser, Acrobat, Preview, etc).

## Automatic layout

`autoLayout` views, and the **Auto layout** button in the diagram editor, use [ELK](https://eclipse.dev/elk/)
(Eclipse Public License 2.0): elements are placed on a grid and relationships are drawn with straight, right-angled lines.
For views without `autoLayout`, open the diagram editor, click **Auto layout**, fine-tune by dragging (with snapping),
and save. To use the old (dagre) layout, add to the view or to `views { properties { ... } }`:

```
properties {
    "structurizr.autolayout" "dagre"
}
```

