TERMUX_PKG_HOMEPAGE=https://proot-me.github.io/
TERMUX_PKG_DESCRIPTION="Static proot for Cellar's APK jniLibs (loader unbundled)"
TERMUX_PKG_LICENSE="GPL-2.0"
TERMUX_PKG_VERSION="5.1.107.87"
TERMUX_PKG_SRCURL=https://github.com/termux/proot/archive/v${TERMUX_PKG_VERSION}.zip
TERMUX_PKG_SHA256=ae5a1b6941e4fe367f825667e446f6916be2bdd9825b000362afafffef50bce5
TERMUX_PKG_DEPENDS="libtalloc-static"
TERMUX_PKG_BUILD_IN_SRC=true
TERMUX_PKG_EXTRA_MAKE_ARGS="-C src"

# Emit the loader as its own file instead of embedding it: inside an app,
# proot cannot extract its embedded loader to /tmp and exec it (Android
# W^X). Cellar ships loader as a second jniLib and sets PROOT_LOADER.
export PROOT_UNBUNDLE_LOADER=$TERMUX_PREFIX/libexec/proot

termux_step_pre_configure() {
	CPPFLAGS+=" -DARG_MAX=131072 -DVERSION=\"${TERMUX_PKG_VERSION}\""
	LDFLAGS+=" -static"
}

termux_step_post_make_install() {
	cp "$TERMUX_PKG_SRCDIR/src/proot" /home/builder/termux-packages/libproot.so
	cp "$TERMUX_PKG_SRCDIR/src/loader/loader" /home/builder/termux-packages/libproot_loader.so
}
