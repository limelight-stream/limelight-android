ANDROID_API_TARGET=21
PARALLEL_JOBS=$(nproc)

rm -r ./android
mkdir android

function build_one
{
PREFIX=$(pwd)/android/$CPU
SYSROOT=$NDK/platforms/android-$ANDROID_API_TARGET/arch-$SYSROOT_CPU
TOOLCHAIN_PATH=$NDK/toolchains/$TOOLCHAIN_DIR/prebuilt/linux-x86_64
export PATH=$PATH:$TOOLCHAIN_PATH/bin
./configure \
    --build=x86_64-unknown-linux-gnu \
    --host=$TOOLCHAIN_BIN_PREFIX \
    --target=$TOOLCHAIN_BIN_PREFIX \
    CFLAGS="--sysroot=$SYSROOT -O2 $ADDI_CFLAGS" \
    $ADDI_CONFIGURE_FLAGS
make clean
make -j$PARALLEL_JOBS
mkdir android/$CPU
cp .libs/libopus.a android/$CPU
}

function build_mips
{
CPU=mips
SYSROOT_CPU=mips
TOOLCHAIN_BIN_PREFIX=mipsel-linux-android
TOOLCHAIN_DIR=mipsel-linux-android-4.9
ADDI_CFLAGS="-mips32 -mhard-float -EL -mno-dsp"
ADDI_CONFIGURE_FLAGS="--enable-fixed-point" # fixed point
build_one
}

function build_mips64
{
CPU=mips64
SYSROOT_CPU=mips64
TOOLCHAIN_BIN_PREFIX=mips64el-linux-android
TOOLCHAIN_DIR=mips64el-linux-android-4.9
ADDI_CFLAGS="-mips64r6"
ADDI_CONFIGURE_FLAGS="--enable-fixed-point" # fixed point
build_one
}

function build_x86
{
CPU=x86
SYSROOT_CPU=x86
TOOLCHAIN_BIN_PREFIX=i686-linux-android
TOOLCHAIN_DIR=x86-4.9
ADDI_CFLAGS="-march=i686 -mtune=atom -mstackrealign -msse -msse2 -msse3 -mssse3 -mfpmath=sse -m32"
ADDI_CONFIGURE_FLAGS="" # floating point for SSE optimizations
build_one
}

function build_x86_64
{
CPU=x86_64
SYSROOT_CPU=x86_64
TOOLCHAIN_BIN_PREFIX=x86_64-linux-android
TOOLCHAIN_DIR=x86_64-4.9
ADDI_CFLAGS="-msse -msse2 -msse3 -mssse3 -msse4 -msse4.1 -msse4.2 -mpopcnt -m64"
ADDI_CONFIGURE_FLAGS="" # floating point for SSE optimizations
build_one
}

function build_armv7
{
CPU=arm
SYSROOT_CPU=arm
TOOLCHAIN_BIN_PREFIX=arm-linux-androideabi
TOOLCHAIN_DIR=arm-linux-androideabi-4.9
ADDI_CFLAGS="-marm -mfpu=vfpv3-d16"
ADDI_LDFLAGS=""
ADDI_CONFIGURE_FLAGS="--enable-fixed-point" # fixed point for NEON, EDSP, Media
build_one
}

# ARMv8 doesn't currently have assembly in the opus project. We still use fixed point
# anyway in the hopes that it will be more performant even without assembly.
function build_armv8
{
CPU=aarch64
SYSROOT_CPU=arm64
TOOLCHAIN_BIN_PREFIX=aarch64-linux-android
TOOLCHAIN_DIR=aarch64-linux-android-4.9
ADDI_CFLAGS=""
ADDI_LDFLAGS=""
ADDI_CONFIGURE_FLAGS="--enable-fixed-point"
build_one
}

build_mips
build_mips64
build_x86
build_x86_64
build_armv7
build_armv8
