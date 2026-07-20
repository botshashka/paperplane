#!/bin/sh
# PaperPlane installer — https://github.com/botshashka/paperplane
# Usage: curl -fsSL https://raw.githubusercontent.com/botshashka/paperplane/main/install.sh | sh
set -eu

main() {
  INSTALL_DIR="$HOME/.paperplane"
  BIN_DIR="$INSTALL_DIR/bin"
  REPO="botshashka/paperplane"

  # ── Check Java ──────────────────────────────────────────────────────

  if ! command -v java >/dev/null 2>&1; then
    echo "Error: Java is not installed."
    echo ""
    echo "PaperPlane requires Java 21 or later."
    echo "Install it from: https://adoptium.net"
    exit 1
  fi

  JAVA_VERSION=$(java -version 2>&1 | head -1 | sed 's/.*"\([0-9]*\).*/\1/')
  if [ "$JAVA_VERSION" -lt 21 ] 2>/dev/null; then
    echo "Error: Java 21+ is required, but found Java $JAVA_VERSION."
    echo ""
    echo "Install Java 21 from: https://adoptium.net"
    exit 1
  fi

  # ── Detect platform ─────────────────────────────────────────────────

  OS=$(uname -s)
  ARCH=$(uname -m)

  case "$OS" in
    Darwin) ;;
    Linux) ;;
    *)
      echo "Error: Unsupported OS: $OS"
      echo "For Windows, use: irm https://raw.githubusercontent.com/$REPO/main/install.ps1 | iex"
      exit 1
      ;;
  esac

  case "$ARCH" in
    x86_64|amd64) ;;
    aarch64|arm64) ;;
    *)
      echo "Error: Unsupported architecture: $ARCH"
      exit 1
      ;;
  esac

  # ── Fetch latest version ────────────────────────────────────────────

  if command -v curl >/dev/null 2>&1; then
    FETCH="curl -fsSL"
  elif command -v wget >/dev/null 2>&1; then
    FETCH="wget -qO-"
  else
    echo "Error: curl or wget is required"
    exit 1
  fi

  if ! command -v unzip >/dev/null 2>&1; then
    echo "Error: unzip is required"
    exit 1
  fi

  echo "Fetching latest version..."
  LATEST=$(${FETCH} "https://api.github.com/repos/$REPO/releases/latest" | grep '"tag_name"' | sed 's/.*"v\([^"]*\)".*/\1/')

  if [ -z "$LATEST" ]; then
    echo "Error: Could not determine latest version"
    exit 1
  fi

  # ── Download and extract ────────────────────────────────────────────

  DOWNLOAD_URL="https://github.com/$REPO/releases/download/v${LATEST}/ppl-${LATEST}.zip"
  TMP_DIR=$(mktemp -d)
  trap 'rm -rf "$TMP_DIR"' EXIT
  TMP_ZIP="$TMP_DIR/ppl.zip"

  echo "Downloading ppl v${LATEST}..."
  if command -v curl >/dev/null 2>&1; then
    curl -fsSL "$DOWNLOAD_URL" -o "$TMP_ZIP"
  else
    wget -q "$DOWNLOAD_URL" -O "$TMP_ZIP"
  fi

  # Clean previous installation but preserve jbr cache
  if [ -d "$INSTALL_DIR/bin" ]; then
    rm -rf "$INSTALL_DIR/bin" "$INSTALL_DIR/lib"
  fi

  mkdir -p "$INSTALL_DIR"
  unzip -qo "$TMP_ZIP" -d "$TMP_DIR/extracted"

  # The zip contains a top-level directory (e.g. ppl-0.1.0/), move contents up
  INNER_DIR=$(find "$TMP_DIR/extracted" -mindepth 1 -maxdepth 1 -type d | head -1)
  cp -r "$INNER_DIR/"* "$INSTALL_DIR/"

  # Make launcher executable
  chmod +x "$BIN_DIR/ppl"

  # ── Set up shell completions ────────────────────────────────────────

  mkdir -p "$INSTALL_DIR/completions"

  SHELL_NAME=$(basename "$SHELL" 2>/dev/null || echo "")

  case "$SHELL_NAME" in
    zsh)
      "$BIN_DIR/ppl" --generate-completion zsh > "$INSTALL_DIR/completions/_ppl" 2>/dev/null || true
      ;;
    bash)
      "$BIN_DIR/ppl" --generate-completion bash > "$INSTALL_DIR/completions/ppl.bash" 2>/dev/null || true
      ;;
    fish)
      FISH_COMP_DIR="$HOME/.config/fish/completions"
      mkdir -p "$FISH_COMP_DIR"
      "$BIN_DIR/ppl" --generate-completion fish > "$FISH_COMP_DIR/ppl.fish" 2>/dev/null || true
      ;;
  esac

  # ── Update PATH ─────────────────────────────────────────────────────

  PATH_ENTRY="export PATH=\"$BIN_DIR:\$PATH\""
  UPDATED_RC=""

  add_to_rc() {
    RC_FILE="$1"
    if [ -f "$RC_FILE" ] && grep -q "\.paperplane/bin" "$RC_FILE"; then
      return
    fi

    EXTRA=""
    # Add completion setup for the appropriate shell
    case "$RC_FILE" in
      *zshrc)
        EXTRA="\nfpath=(\"$INSTALL_DIR/completions\" \$fpath)"
        ;;
      *bashrc)
        if [ -f "$INSTALL_DIR/completions/ppl.bash" ]; then
          EXTRA="\nsource \"$INSTALL_DIR/completions/ppl.bash\""
        fi
        ;;
    esac

    printf "\n# paperplane\n%s%b\n" "$PATH_ENTRY" "$EXTRA" >> "$RC_FILE"
    UPDATED_RC="$RC_FILE"
  }

  case "$SHELL_NAME" in
    zsh)  add_to_rc "$HOME/.zshrc" ;;
    bash) add_to_rc "$HOME/.bashrc" ;;
    fish)
      # fish uses a different config mechanism
      FISH_CONFIG="$HOME/.config/fish/conf.d/paperplane.fish"
      if [ ! -f "$FISH_CONFIG" ]; then
        mkdir -p "$(dirname "$FISH_CONFIG")"
        echo "set -gx PATH $BIN_DIR \$PATH" > "$FISH_CONFIG"
        UPDATED_RC="$FISH_CONFIG"
      fi
      ;;
    *)    add_to_rc "$HOME/.profile" ;;
  esac

  # ── Done ────────────────────────────────────────────────────────────

  echo ""
  echo "  paperplane was installed successfully to ~/.paperplane"
  echo ""
  if [ -n "$UPDATED_RC" ]; then
    echo "  Added ~/.paperplane/bin to PATH in ~/${UPDATED_RC##*/}"
    echo ""
  fi
  echo "  To get started, run:"
  echo "    ppl --help"
  echo ""
  if [ -n "$UPDATED_RC" ]; then
    echo "  You may need to restart your terminal or run:"
    echo "    source ~/${UPDATED_RC##*/}"
    echo ""
  fi
}

main
