HOW TO PLACE COMPILED PROOT BINARIES:
==================================================
1. Download compiled 'proot' binary for arm64-v8a from Termux packages:
   https://github.com/termux/proot/releases OR packages.termux.dev
2. Rename the binary to 'libproot.so' (IMPORTANT for Android 10+ W^X workaround)
3. Place in:
   app/src/main/jniLibs/arm64-v8a/libproot.so
   app/src/main/jniLibs/armeabi-v7a/libproot.so

HOW TO PLACE BOOTSTRAP ROOTFS ARCHIVE:
==================================================
1. Download or package the Termux Node.js bootstrap archive:
   Containing: /bin/node, /bin/npm, /bin/bash, /lib/node_modules/npm, /lib/libnode.so
2. Compress as .tar.xz or .tar.gz
3. Place in:
   app/src/main/assets/bootstrap/bootstrap-arm64-v8a.tar.xz
