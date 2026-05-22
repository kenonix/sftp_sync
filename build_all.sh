#!/usr/bin/env bash

# ==============================================================================
# SFTP Sync Multi-Platform Package Builder
# ==============================================================================
# OS: Windows/Linux/macOS compatible build script
# ==============================================================================

# ANSI Color Codes for Premium Console UI
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
MAGENTA='\033[0;35m'
CYAN='\033[0;36m'
BOLD='\033[1m'
NC='\033[0m' # No Color

# Clear Screen & Print Banner
clear
echo -e "${CYAN}${BOLD}======================================================================${NC}"
echo -e "${CYAN}${BOLD}     ⚡ SFTP Sync Multi-Platform Package Builder (All-In-One) ⚡${NC}"
echo -e "${CYAN}${BOLD}======================================================================${NC}"
echo ""

# Define Output Directories
OUTPUT_DIR="build-outputs"
ANDROID_OUT="$OUTPUT_DIR/android"
LINUX_OUT="$OUTPUT_DIR/linux"
WINDOWS_OUT="$OUTPUT_DIR/windows"

# Create Output Folders
mkdir -p "$ANDROID_OUT"
mkdir -p "$LINUX_OUT"
mkdir -p "$WINDOWS_OUT"

# 1. Detect Host OS
OS_NAME=$(uname -s 2>/dev/null || echo "Windows")
echo -e "${BLUE}${BOLD}[1/4] OS & Build Environment Diagnostics${NC}"
echo -e "  • Detected Host OS : ${BOLD}${OS_NAME}${NC}"

# Check packaging utilities
HAS_RPMBUILD=false
HAS_DPKG=false

if [[ "$OS_NAME" == *"Linux"* ]]; then
    if command -v rpmbuild &>/dev/null; then
        HAS_RPMBUILD=true
    fi
    if command -v dpkg-deb &>/dev/null || command -v dpkg &>/dev/null; then
        HAS_DPKG=true
    fi
fi

# Print diagnostics table
echo -e "  • Packaging Tools Status:"
if [ "$HAS_RPMBUILD" = true ]; then
    echo -e "    - rpmbuild   : ${GREEN}${BOLD}[✓] INSTALLED${NC} (RPM builds enabled)"
else
    echo -e "    - rpmbuild   : ${YELLOW}[-] MISSING${NC} (RPM builds will be skipped)"
fi

if [ "$HAS_DPKG" = true ]; then
    echo -e "    - dpkg/fakeroot: ${GREEN}${BOLD}[✓] INSTALLED${NC} (DEB builds enabled)"
else
    echo -e "    - dpkg/fakeroot: ${YELLOW}[-] MISSING${NC} (DEB builds will be skipped)"
fi
echo ""

# 2. Compile Android Package (APK)
echo -e "${BLUE}${BOLD}[2/4] Compiling Android Package (.apk)${NC}"
echo -e "  • Executing: ./gradlew :composeApp:assembleDebug..."
if ./gradlew :composeApp:assembleDebug; then
    APK_FILE=$(find composeApp/build/outputs/apk/debug/ -name "*.apk" | head -n 1)
    if [ -f "$APK_FILE" ]; then
        cp "$APK_FILE" "$ANDROID_OUT/SftpSync-debug.apk"
        echo -e "  • ${GREEN}${BOLD}[✓] SUCCESS!${NC} Android package copied to: ${BOLD}$ANDROID_OUT/SftpSync-debug.apk${NC}"
    else
        echo -e "  • ${RED}${BOLD}[✗] ERROR!${NC} Built APK not found!"
    fi
else
    echo -e "  • ${RED}${BOLD}[✗] FAILED!${NC} Android compilation failed."
fi
echo ""

# 3. Compile Linux Desktop Packages (RPM / DEB)
echo -e "${BLUE}${BOLD}[3/4] Compiling Linux Desktop Packages (.rpm / .deb)${NC}"

# RPM Package
if [ "$HAS_RPMBUILD" = true ]; then
    echo -e "  • Executing: ./gradlew :composeApp:packageRpm..."
    if ./gradlew :composeApp:packageRpm; then
        RPM_FILE=$(find composeApp/build/compose/binaries/main/rpm/ -name "*.rpm" | head -n 1)
        if [ -f "$RPM_FILE" ]; then
            cp "$RPM_FILE" "$LINUX_OUT/"
            echo -e "  • ${GREEN}${BOLD}[✓] SUCCESS!${NC} RPM package copied to: ${BOLD}$LINUX_OUT/$(basename "$RPM_FILE")${NC}"
        else
            echo -e "  • ${RED}${BOLD}[✗] ERROR!${NC} Built RPM file not found!"
        fi
    else
        echo -e "  • ${RED}${BOLD}[✗] FAILED!${NC} RPM compilation failed."
    fi
else
    echo -e "  • ${YELLOW}${BOLD}[!] SKIPPED: RPM${NC} (rpmbuild utility is not installed)"
fi

# DEB Package
if [ "$HAS_DPKG" = true ]; then
    echo -e "  • Executing: ./gradlew :composeApp:packageDeb..."
    if ./gradlew :composeApp:packageDeb; then
        DEB_FILE=$(find composeApp/build/compose/binaries/main/deb/ -name "*.deb" | head -n 1)
        if [ -f "$DEB_FILE" ]; then
            cp "$DEB_FILE" "$LINUX_OUT/"
            echo -e "  • ${GREEN}${BOLD}[✓] SUCCESS!${NC} DEB package copied to: ${BOLD}$LINUX_OUT/$(basename "$DEB_FILE")${NC}"
        else
            echo -e "  • ${RED}${BOLD}[✗] ERROR!${NC} Built DEB file not found!"
        fi
    else
        echo -e "  • ${RED}${BOLD}[✗] FAILED!${NC} DEB compilation failed."
    fi
else
    echo -e "  • ${YELLOW}${BOLD}[!] SKIPPED: DEB${NC} (dpkg-dev / fakeroot utilities are missing)"
    echo -e "    ${MAGENTA}💡 TIP: To enable Debian (.deb) package compilation on Fedora/RedHat, run:${NC}"
    echo -e "       ${BOLD}sudo dnf install dpkg-dev fakeroot${NC}"
fi
echo ""

# 4. Windows Desktop Packages (.msi / .exe)
echo -e "${BLUE}${BOLD}[4/4] Windows Desktop Packages (.msi / .exe)${NC}"
if [[ "$OS_NAME" == *"Windows"* || "$OS_NAME" == *"MINGW"* || "$OS_NAME" == *"CYGWIN"* || "$OS_NAME" == *"MSYS"* ]]; then
    echo -e "  • Executing: ./gradlew :composeApp:packageMsi :composeApp:packageExe..."
    if ./gradlew :composeApp:packageMsi :composeApp:packageExe; then
        MSI_FILE=$(find composeApp/build/compose/binaries/main/msi/ -name "*.msi" | head -n 1)
        EXE_FILE=$(find composeApp/build/compose/binaries/main/exe/ -name "*.exe" | head -n 1)
        
        if [ -f "$MSI_FILE" ]; then cp "$MSI_FILE" "$WINDOWS_OUT/"; fi
        if [ -f "$EXE_FILE" ]; then cp "$EXE_FILE" "$WINDOWS_OUT/"; fi
        
        echo -e "  • ${GREEN}${BOLD}[✓] SUCCESS!${NC} Windows packages compiled in: ${BOLD}$WINDOWS_OUT/${NC}"
    else
        echo -e "  • ${RED}${BOLD}[✗] FAILED!${NC} Windows installer packaging failed."
    fi
else
    echo -e "  • ${YELLOW}${BOLD}[!] SKIPPED: MSI & EXE${NC} (Building native Windows installers requires running on a Windows host with WiX Toolset)"
fi
echo ""

# Print Final Packaging Summary
echo -e "${CYAN}${BOLD}======================================================================${NC}"
echo -e "${CYAN}${BOLD}                   ✨ Multi-Platform Build Summary ✨${NC}"
echo -e "${CYAN}${BOLD}======================================================================${NC}"
echo -e " All compiled distributions have been placed in the ${BOLD}${OUTPUT_DIR}/${NC} directory:"
echo ""

# List output directory files
if [ -d "$OUTPUT_DIR" ]; then
    find "$OUTPUT_DIR" -type f | while read -r file; do
        SIZE=$(du -h "$file" | cut -f1)
        echo -e "  ${GREEN}[✓]${NC} ${BOLD}$(basename "$file")${NC} (Size: $SIZE) -> [${file}]"
    done
fi
echo -e "${CYAN}${BOLD}======================================================================${NC}"
echo -e "  Have an absolutely beautiful day of multiplatform synchronization! 🚀"
echo -e "${CYAN}${BOLD}======================================================================${NC}"
