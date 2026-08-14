#!/usr/bin/env bash
# Build the Mesh User Guide as HTML + PDF.
#
# Uses the official asciidoctor Docker image, which bundles asciidoctor,
# asciidoctor-pdf, asciidoctor-diagram, PlantUML, and Graphviz. No local
# Java / Ruby install needed — just Docker.
#
# Outputs land in ./build/ (gitignored):
#   build/mesh-user-guide.html
#   build/mesh-user-guide.pdf
#   build/images/*.svg          (rendered PlantUML diagrams)
set -euo pipefail
cd "$(dirname "$0")"

mkdir -p build

DOCKER_IMAGE="${MESH_DOCS_IMAGE:-asciidoctor/docker-asciidoctor:1.71}"

# Ensure the image is present (pulls only on first run).
docker image inspect "$DOCKER_IMAGE" > /dev/null 2>&1 || docker pull "$DOCKER_IMAGE"

echo "[build] rendering HTML..."
docker run --rm -v "$(pwd):/documents" -w /documents "$DOCKER_IMAGE" \
    asciidoctor \
        -r asciidoctor-diagram \
        -a data-uri \
        -a allow-uri-read \
        -D build \
        mesh-user-guide.adoc

echo "[build] rendering PDF..."
docker run --rm -v "$(pwd):/documents" -w /documents "$DOCKER_IMAGE" \
    asciidoctor-pdf \
        -r asciidoctor-diagram \
        -a allow-uri-read \
        -D build \
        mesh-user-guide.adoc

echo
echo "[ok] outputs:"
ls -lh build/mesh-user-guide.* build/images/ 2>/dev/null | sed 's/^/   /'
echo
echo "Open the HTML with:   open build/mesh-user-guide.html"
echo "Open the PDF with:    open build/mesh-user-guide.pdf"
