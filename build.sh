#!/bin/bash

# ==============================================================================
# MarkFlow Android Build Script
# 描述: 自动化环境检查、编译并生成可安装的 Debug APK
# 用法: chmod +x build.sh && ./build.sh
# ==============================================================================

set -e # 遇到错误立即退出

# ---------------------- 配置区域 ----------------------
APP_NAME="MarkFlow"
OUTPUT_DIR="./apk_output"
GRADLE_TASK="assembleDebug" # 如需 Release 版请改为 assembleRelease
JAVA_MIN_VERSION=17
# ----------------------------------------------------

# 颜色定义
RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m' # No Color

log_info() { echo -e "${BLUE}[INFO]${NC} $1"; }
log_success() { echo -e "${GREEN}[SUCCESS]${NC} $1"; }
log_warn() { echo -e "${YELLOW}[WARN]${NC} $1"; }
log_error() { echo -e "${RED}[ERROR]${NC} $1"; exit 1; }

# 1. 环境前置检查
check_environment() {
    log_info "正在检查构建环境..."

    # 检查 Java
    if ! command -v java &> /dev/null; then
        log_error "未检测到 Java，请安装 JDK $JAVA_MIN_VERSION+"
    fi
    
    JAVA_VER=$(java -version 2>&1 | head -n 1 | awk -F '"' '{print $2}' | cut -d'.' -f1)
    if [ "$JAVA_VER" -lt "$JAVA_MIN_VERSION" ]; then
        log_error "Java 版本过低 (当前: $JAVA_VER)，MarkFlow 需要 JDK $JAVA_MIN_VERSION+"
    fi
    log_success "Java 环境正常 (Version: $JAVA_VER)"

    # 检查 ANDROID_HOME
    if [ -z "$ANDROID_HOME" ] && [ -z "$ANDROID_SDK_ROOT" ]; then
        log_warn "未设置 ANDROID_HOME 环境变量，尝试使用默认路径..."
        if [ -d "$HOME/Android/Sdk" ]; then
            export ANDROID_HOME="$HOME/Android/Sdk"
        elif [ -d "$HOME/Library/Android/sdk" ]; then
            export ANDROID_HOME="$HOME/Library/Android/sdk"
        else
            log_error "无法找到 Android SDK，请设置 ANDROID_HOME 环境变量"
        fi
    fi
    log_success "Android SDK 路径: ${ANDROID_HOME:-$ANDROID_SDK_ROOT}"

    # 检查 Gradle Wrapper
    if [ ! -f "./gradlew" ]; then
        log_error "未找到 gradlew 文件，请确保在项目根目录下运行此脚本"
    fi
    chmod +x ./gradlew
}

# 2. 清理旧构建产物
clean_project() {
    log_info "清理旧的构建缓存..."
    ./gradlew clean --quiet
    rm -rf "$OUTPUT_DIR"
    mkdir -p "$OUTPUT_DIR"
}

# 3. 执行编译
build_apk() {
    log_info "开始编译 $APP_NAME ($GRADLE_TASK)..."
    log_info "首次编译可能需要 10-30 分钟下载依赖，请耐心等待..."
    
    START_TIME=$(date +%s)
    
    # 执行 Gradle 构建，--no-daemon 避免守护进程内存问题
    ./gradlew $GRADLE_TASK --no-daemon --stacktrace
    
    END_TIME=$(date +%s)
    ELAPSED=$((END_TIME - START_TIME))
    log_success "编译完成！耗时: ${ELAPSED}s"
}

# 4. 提取并整理 APK
collect_apk() {
    log_info "正在收集 APK 文件..."
    
    APK_SOURCE="app/build/outputs/apk/debug/app-debug.apk"
    TIMESTAMP=$(date +"%Y%m%d_%H%M%S")
    TARGET_APK="$OUTPUT_DIR/${APP_NAME}_debug_${TIMESTAMP}.apk"

    if [ ! -f "$APK_SOURCE" ]; then
        # 尝试查找其他可能的输出位置
        APK_SOURCE=$(find app/build/outputs/apk -name "*.apk" -type f | head -n 1)
    fi

    if [ -z "$APK_SOURCE" ] || [ ! -f "$APK_SOURCE" ]; then
        log_error "构建成功但未找到 APK 文件，请检查构建日志"
    fi

    cp "$APK_SOURCE" "$TARGET_APK"
    
    # 获取文件大小
    FILE_SIZE=$(du -h "$TARGET_APK" | cut -f1)
    
    echo ""
    echo "=========================================="
    log_success "🎉 $APP_NAME APK 构建成功!"
    echo "=========================================="
    echo -e " 📦 文件路径: ${GREEN}$TARGET_APK${NC}"
    echo -e " 📏 文件大小: ${GREEN}$FILE_SIZE${NC}"
    echo -e " ⏱️  构建耗时: ${GREEN}${ELAPSED}s${NC}"
    echo "=========================================="
    echo ""
    log_info "安装到手机: adb install $TARGET_APK"
}

# ---------------------- 主流程 ----------------------
main() {
    echo ""
    echo "╔══════════════════════════════════════╗"
    echo "║       MarkFlow Build System          ║"
    echo "╚══════════════════════════════════════╝"
    echo ""
    
    check_environment
    clean_project
    build_apk
    collect_apk
}

# 捕获中断信号
trap 'log_warn "构建被用户中断"; exit 130' INT TERM

main "$@"