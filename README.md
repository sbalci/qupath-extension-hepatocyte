# qupath-extension-hepatocyte

[![License: Apache-2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](https://www.apache.org/licenses/LICENSE-2.0)
[![Build](https://github.com/sbalci/qupath-extension-hepatocyte/actions/workflows/build.yml/badge.svg)](https://github.com/sbalci/qupath-extension-hepatocyte/actions/workflows/build.yml)
[![Release](https://img.shields.io/github/v/release/sbalci/qupath-extension-hepatocyte)](https://github.com/sbalci/qupath-extension-hepatocyte/releases/latest)

A [QuPath](https://qupath.github.io/) **0.6+** extension that packages the
[hepatocyte-app](https://github.com/sbalci/hepatocyte-app) deep-learning liver
H&E segmentation workflow as one-click menu entries under **Extensions →
Hepatocyte**.

> Modeled after the official [QuPath extension template](https://github.com/qupath/qupath-extension-template).

## What it does

After installation, an **Extensions → Hepatocyte** submenu appears:

```
Extensions
└── Hepatocyte
    ├── — Hepatocyte segmentation —
    ├── ─────────────────────
    ├── 1. Import slides from folder…
    ├── 2. Run inference on selected annotations
    ├── ─────────────────────
    ├── Coming in a later release ▸
    │     ├── Run inference on whole project (unlabelled / tissue)
    │     └── Auto-detect tissue (current slide / whole project)
    ├── ─────────────────────
    ├── Settings…
    ├── Environment check…
    ├── ─────────────────────
    └── About / Help…
```

- **1. Import slides from folder…** — pick a folder; recursively find every
  `.svs`, add the new ones to the open project, set every image to brightfield
  H&E. Idempotent.
- **2. Run inference on selected annotations** — run the segmentation model on
  each selected area annotation. A single-window wizard writes a request, calls
  the Python bridge, imports the predicted polygons back as classified
  annotations, and writes per-class cell counts to each parent ROI (plus an
  Excel report under `<project>/reports/<slide>/` when a project is open).

The model emits five morphological classes — **Balloon dystrophy**,
**Mesenchymal**, **Non-nuclear**, **Normal**, **Steatosis**. Output is a
deep-learning prediction and must be verified visually; no clinical grade or
score is derived.

## How inference runs

The model runs **outside QuPath**. The extension bundles only the Groovy UI —
it does not bundle Python, the model, or the repo. On "Run inference" it writes
a request JSON, runs `hepatocyte_qupath_inference.py` in your hepatocyte-app
venv (via `ProcessBuilder`), and imports the returned GeoJSON. That bridge
script imports `morphometry` from the hepatocyte-app repo, so a local checkout
is required.

You therefore need, once:

1. A local checkout of **[hepatocyte-app](https://github.com/sbalci/hepatocyte-app)** with its Python **venv** installed (`pip install -r requirements.txt`).
2. The **TorchScript model checkpoint** (`baseline-1-jit.pth`, ~180 MB — 🔒 available on request from the maintainer).

Then set the three paths in **Extensions → Hepatocyte → Settings…**:

| Setting | Example |
|---|---|
| Python (venv python.exe) | `K:/hepatocyte-app/venv/Scripts/python.exe` |
| Bridge (inference script) | `K:/hepatocyte-app/qupath/hepatocyte_qupath_inference.py` |
| Model checkpoint (.pth) | `K:/hepatocyte-app/weights/baseline-1-jit.pth` |

Settings persist across QuPath restarts (stored in a `java.util.prefs` node
shared between the Java settings dialog and the bundled scripts). Use
**Environment check…** to confirm all three paths resolve before running.

## Requirements

- **QuPath 0.6.0** or newer (baseline targeted by this build).
- A local hepatocyte-app checkout + venv + model checkpoint (see above).
- A local, openslide-readable slide (`.svs` / `.tiff`) — the bridge opens the
  slide itself, so non-file image sources (network/OMERO) are not supported.

## Install

**From a release JAR:**

1. Download `qupath-extension-hepatocyte-<version>.jar` from
   [Releases](https://github.com/sbalci/qupath-extension-hepatocyte/releases/latest).
2. Drag the JAR onto the QuPath main window (or copy it into your QuPath
   extensions directory).
3. Restart QuPath. The **Extensions → Hepatocyte** menu appears.

**Via the QuPath extension catalog / manager:** add this repository's
[`catalog.json`](catalog.json) URL in *Extensions → Manage extensions → catalogs*,
then install "QuPath Hepatocyte extension".

## Build from source

Requires JDK 21. QuPath APIs are fetched from SciJava Maven at build time.

```bash
./gradlew clean build
# → build/libs/qupath-extension-hepatocyte-<version>.jar
```

## Releasing

Tagging a `v*` tag (or running the **Release** workflow with a tag input)
builds the JAR and publishes a GitHub Release with it attached. Bump the version
in **all** of these to keep them in lockstep:

- `build.gradle.kts` (`version`)
- `HepatocyteExtension.java` (`EXTENSION_VERSION`)
- `catalog.json` (release `name` + `main_url` filename)
- `CITATION.cff` (`version`)
- `.zenodo.json` is version-independent (Zenodo stamps the version on deposit)

The `catalog.json` `main_url` must match the released JAR filename exactly
(`qupath-extension-hepatocyte-<version>.jar`), or the extension manager's
install will 404.

## Citation

If you use this software in published work, cite it using the metadata in
[`CITATION.cff`](CITATION.cff), and cite the underlying
[hepatocyte-app](https://github.com/sbalci/hepatocyte-app) model and
[QuPath](https://doi.org/10.1038/s41598-017-17204-5).

## License

[Apache-2.0](LICENSE). **Research and education only — not for clinical
decision-making.**
