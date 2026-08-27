import os
import urllib.request
import io
import tarfile
import subprocess
import struct

MIRROR_BASE = "https://packages.termux.dev/apt/termux-main"

DOWNLOAD_MAPS = [
    # --- ARM64-V8A ---
    {
        "dest": "app/src/main/jniLibs/arm64-v8a/libproot.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.92_aarch64.deb",
        "tar_path": "./data/data/com.termux/files/usr/bin/proot"
    },
    {
        "dest": "app/src/main/jniLibs/arm64-v8a/libproot-loader.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.92_aarch64.deb",
        "tar_path": "./data/data/com.termux/files/usr/libexec/proot/loader"
    },
    {
        "dest": "app/src/main/jniLibs/arm64-v8a/libproot-loader32.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.92_aarch64.deb",
        "tar_path": "./data/data/com.termux/files/usr/libexec/proot/loader32"
    },
    {
        "dest": "app/src/main/jniLibs/arm64-v8a/libbusybox.so",
        "url": f"{MIRROR_BASE}/pool/main/b/busybox/busybox_1.38.0-1_aarch64.deb",
        "tar_path": "./data/data/com.termux/files/usr/bin/busybox"
    },
    {
        "dest": "app/src/main/jniLibs/arm64-v8a/libtalloc.so",
        "url": f"{MIRROR_BASE}/pool/main/libt/libtalloc/libtalloc_2.4.3_aarch64.deb",
        "tar_path": "./data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3"
    },
    {
        "dest": "app/src/main/jniLibs/arm64-v8a/libandroid-shmem.so",
        "url": f"{MIRROR_BASE}/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_aarch64.deb",
        "tar_path": "./data/data/com.termux/files/usr/lib/libandroid-shmem.so"
    },

    # --- X86_64 ---
    {
        "dest": "app/src/main/jniLibs/x86_64/libproot.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.92_x86_64.deb",
        "tar_path": "./data/data/com.termux/files/usr/bin/proot"
    },
    {
        "dest": "app/src/main/jniLibs/x86_64/libproot-loader.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.92_x86_64.deb",
        "tar_path": "./data/data/com.termux/files/usr/libexec/proot/loader"
    },
    {
        "dest": "app/src/main/jniLibs/x86_64/libproot-loader32.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.92_x86_64.deb",
        "tar_path": "./data/data/com.termux/files/usr/libexec/proot/loader32"
    },
    {
        "dest": "app/src/main/jniLibs/x86_64/libbusybox.so",
        "url": f"{MIRROR_BASE}/pool/main/b/busybox/busybox_1.38.0-1_x86_64.deb",
        "tar_path": "./data/data/com.termux/files/usr/bin/busybox"
    },
    {
        "dest": "app/src/main/jniLibs/x86_64/libtalloc.so",
        "url": f"{MIRROR_BASE}/pool/main/libt/libtalloc/libtalloc_2.4.3_x86_64.deb",
        "tar_path": "./data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3"
    },
    {
        "dest": "app/src/main/jniLibs/x86_64/libandroid-shmem.so",
        "url": f"{MIRROR_BASE}/pool/main/liba/libandroid-shmem/libandroid-shmem_0.7_x86_64.deb",
        "tar_path": "./data/data/com.termux/files/usr/lib/libandroid-shmem.so"
    }
]

def download_deb(url, local_path):
    print(f"Downloading {url}...")
    subprocess.run(["curl", "-L", "-s", "-o", local_path, url], check=True)

def parse_ar_and_get_data_tar(deb_path):
    with open(deb_path, "rb") as f:
        deb_data = f.read()
    if not deb_data.startswith(b"!<arch>\n"):
        raise ValueError(f"File {deb_path} is not a valid ar archive")
    idx = 8
    while idx < len(deb_data):
        header = deb_data[idx:idx+60]
        if len(header) < 60:
            break
        file_name = header[0:16].decode("ascii").strip()
        file_size = int(header[48:58].decode("ascii").strip())
        idx += 60
        file_data = deb_data[idx:idx+file_size]
        idx += file_size
        if file_size % 2 != 0:
            idx += 1
        if file_name.startswith("data.tar"):
            return file_name, file_data
    return None, None

def extract_from_tar(tar_bytes, internal_path, dest_path):
    with tarfile.open(fileobj=io.BytesIO(tar_bytes), mode="r") as tar:
        try:
            member = tar.getmember(internal_path)
            if member.issym() or member.islnk():
                link_target = member.linkname
                resolved_path = link_target
                if not link_target.startswith("./data/"):
                    parent_dir = os.path.dirname(internal_path)
                    resolved_path = os.path.normpath(os.path.join(parent_dir, link_target))
                print(f"Resolving symlink: {internal_path} -> {resolved_path}")
                member = tar.getmember(resolved_path)
            
            f_in = tar.extractfile(member)
            if f_in is None:
                raise ValueError(f"Could not read member file {internal_path}")
            
            os.makedirs(os.path.dirname(dest_path), exist_ok=True)
            with open(dest_path, "wb") as f_out:
                f_out.write(f_in.read())
            print(f"Extracted and saved: {dest_path}")
            return True
        except KeyError:
            print(f"Error: {internal_path} not found")
            return False

def main():
    temp_deb = "temp.deb"
    for item in DOWNLOAD_MAPS:
        dest = item["dest"]
        url = item["url"]
        tar_path = item["tar_path"]
        print(f"Processing: {dest}")
        try:
            download_deb(url, temp_deb)
            tar_name, tar_data = parse_ar_and_get_data_tar(temp_deb)
            if tar_data is not None:
                extract_from_tar(tar_data, tar_path, dest)
        except Exception as e:
            print(f"Failed {dest}: {e}")
        finally:
            if os.path.exists(temp_deb):
                os.remove(temp_deb)

if __name__ == "__main__":
    main()
