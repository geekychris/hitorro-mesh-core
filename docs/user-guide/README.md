# Mesh User Guide

Comprehensive AsciiDoc source for the Hitorro Mesh user guide.
Diagrams are embedded PlantUML blocks rendered at build time via
`asciidoctor-diagram`. Output targets: HTML (single-file with inline
SVGs) and PDF.

## Build

Requires only Docker (all AsciiDoc / PlantUML / Graphviz tooling
comes from a pre-built image):

```bash
./build.sh
```

Outputs land in `build/`:

- `mesh-user-guide.html` — single-file HTML, images inlined
- `mesh-user-guide.pdf` — print-ready PDF
- `images/*.svg` — the rendered diagram assets

Open with:
```bash
open build/mesh-user-guide.html    # or .pdf
```

Override the Docker image via `MESH_DOCS_IMAGE=your-image ./build.sh`
if you want to pin a different asciidoctor version.

## Editing

- Source: `mesh-user-guide.adoc` (one file, ~600 lines)
- Diagrams: embedded PlantUML blocks like
  ```
  [plantuml, diagram-name, svg]
  ....
  @startuml
    ... plantuml source ...
  @enduml
  ....
  ```
  asciidoctor-diagram renders each into `build/images/<name>.svg`.

- No local install required — every build re-runs in the Docker image
  for reproducibility.
