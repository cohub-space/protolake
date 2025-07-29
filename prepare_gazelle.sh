#!/bin/bash
# Script to prepare protolake-gazelle for distribution

set -euo pipefail

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
GAZELLE_DIR="$SCRIPT_DIR/../protolake-gazelle"

echo "=== Preparing protolake-gazelle for distribution ==="

# Option 1: Push to GitHub
echo ""
echo "Option 1: Push to GitHub"
echo "1. Create a GitHub repository: github.com/yourorg/protolake-gazelle"
echo "2. Push the code:"
echo "   cd $GAZELLE_DIR"
echo "   git init"
echo "   git add ."
echo "   git commit -m 'Initial commit'"
echo "   git remote add origin https://github.com/yourorg/protolake-gazelle.git"
echo "   git push -u origin main"
echo "3. Use these environment variables when creating lakes:"
echo "   export PROTOLAKE_GAZELLE_GIT_URL=https://github.com/yourorg/protolake-gazelle.git"
echo "   export PROTOLAKE_GAZELLE_GIT_COMMIT=<commit-sha>"

# Option 2: Create a tarball for offline use
echo ""
echo "Option 2: Create tarball for offline distribution"
cd "$SCRIPT_DIR/.."
tar czf protolake-gazelle.tar.gz \
  --exclude='*.bazel-*' \
  --exclude='.git' \
  --exclude='.ijwb' \
  protolake-gazelle/

echo "Created: $SCRIPT_DIR/../protolake-gazelle.tar.gz"
echo "To use: Upload to a web server and use archive_override in MODULE.bazel"

# Option 3: Use as-is for development
echo ""
echo "Option 3: Local development"
echo "Current setup works with:"
echo "   export PROTOLAKE_GAZELLE_SOURCE_PATH=$GAZELLE_DIR"
echo "Or use default relative path: ../../../protolake-gazelle"

echo ""
echo "=== Configuration Examples ==="
echo ""
echo "For production (GitHub):"
echo "  docker run -e PROTOLAKE_GAZELLE_GIT_URL=https://github.com/yourorg/protolake-gazelle.git \\"
echo "             -e PROTOLAKE_GAZELLE_GIT_COMMIT=abc123 \\"
echo "             protolake"
echo ""
echo "For Docker development:"
echo "  # In docker-compose.yml, already configured as:"
echo "  # - PROTOLAKE_GAZELLE_SOURCE_PATH=/protolake-gazelle"
echo ""
echo "For local development:"
echo "  # No configuration needed, uses relative path"
