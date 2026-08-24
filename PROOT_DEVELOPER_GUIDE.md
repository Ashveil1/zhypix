# PRoot and Native Binaries Developer Guide
*(คู่มือนักพัฒนาและการจัดการ Native Binaries สำหรับระบบจำลอง Linux Terminal)*

เอกสารฉบับนี้จัดทำขึ้นเพื่อบันทึกประวัติการแก้ไขปัญหาเชิงลึก โครงสร้างสถาปัตยกรรมของ Native Binaries และขั้นตอนการกู้คืนหากเกิดปัญหาขึ้นอีกในอนาคต เพื่อให้ทีมพัฒนาและผู้ร่วมพัฒนาท่านอื่นสามารถบำรุงรักษาและพัฒนาต่อยอดระบบนี้ได้อย่างราบรื่น

---

## 1. ปัญหาหลักที่พบ (The Core Issue)
ในการรันระบบจำลอง Linux Terminal (PRoot) บนแอปพลิเคชัน Android เครื่องจำลองไม่สามารถเรียกทำงานคำสั่งหรือเข้าใช้งาน Distro ได้ โดยเกิดข้อผิดพลาดประเภท `Exec format error` หรือสิทธิการเข้าถึงไฟล์ล้มเหลว

### สาเหตุที่แท้จริง (Root Cause):
จากการใช้คำสั่ง `file` ตรวจสอบไฟล์ไบนารีดั้งเดิมที่แถมมาในไดเรกทอรี `app/src/main/jniLibs` ทั้งของ `arm64-v8a` และ `x86_64` พบว่า:
- **Header ของไฟล์ ELF เสียหาย**: ตัวอย่างเช่น `corrupted program header size` และ `corrupted section header size`
- ไฟล์เหล่านี้อาจจะเสียหายจากการแปลงไฟล์ผ่าน Git หรือระบบ Transport บางอย่างระหว่างการนำเข้าโครงงาน (Importing) หรือการบีบอัด ส่งผลให้ระบบปฏิบัติการแอนดรอยด์มองว่าไฟล์ไบนารีเหล่านั้นไม่ใช่ไฟล์ ELF ที่ถูกต้องและไม่สามารถโหลดหรือรันได้

---

## 2. วิธีการแก้ไขปัญหาอย่างถาวร (The Permanent Solution)
เราได้ทำการเปลี่ยนไฟล์ Native Binaries (`.so`) ทั้งหมดใน `app/src/main/jniLibs` โดยดาวน์โหลดจาก **Official Pristine Packages** ของระบบ **Termux** ซึ่งรับประกันความเข้ากันได้ 100% บน Android

### แหล่งอ้างอิงและคลังจัดเก็บไฟล์ (Mirror Source):
ดาวน์โหลดไฟล์ `.deb` ของสถาปัตยกรรมที่แท้จริงจากคลังกระจายแพ็กเกจทางการของ USTC Termux Mirror:
- **PRoot**: `https://mirrors.ustc.edu.cn/termux/apt/termux-main/pool/main/p/proot/`
- **Busybox**: `https://mirrors.ustc.edu.cn/termux/apt/termux-main/pool/main/b/busybox/`
- **Libtalloc**: `https://mirrors.ustc.edu.cn/termux/apt/termux-main/pool/main/libt/libtalloc/`
- **Libandroid-shmem**: `https://mirrors.ustc.edu.cn/termux/apt/termux-main/pool/main/liba/libandroid-shmem/`

### วิธีสกัดไฟล์จากไฟล์ `.deb`:
1. ไฟล์ `.deb` คือไฟล์อาร์ไคฟ์แบบ `ar`
2. ภายในจะมีไฟล์ `data.tar.xz` ซึ่งเก็บโครงสร้างระบบไฟล์และไฟล์ไบนารีที่แท้จริงไว้
3. สกัดไฟล์ไบนารีที่ถูกต้องออกมา และย้ายเข้าสู่ไดเรกทอรีโครงการดังนี้:

| Target File (`app/src/main/jniLibs/...`) | Source `.deb` | Path inside `data.tar.xz` |
|---|---|---|
| `arm64-v8a/libproot.so` | `proot_..._aarch64.deb` | `./data/data/com.termux/files/usr/bin/proot` |
| `arm64-v8a/libproot-loader.so` | `proot_..._aarch64.deb` | `./data/data/com.termux/files/usr/libexec/proot/loader` |
| `arm64-v8a/libproot-loader32.so` | `proot_..._aarch64.deb` | `./data/data/com.termux/files/usr/libexec/proot/loader32` |
| `arm64-v8a/libbusybox.so` | `busybox_..._aarch64.deb` | `./data/data/com.termux/files/usr/bin/busybox` |
| `arm64-v8a/libtalloc.so` | `libtalloc_..._aarch64.deb` | `./data/data/com.termux/files/usr/lib/libtalloc.so.2.4.3` |
| `arm64-v8a/libandroid-shmem.so` | `libandroid-shmem_..._aarch64.deb` | `./data/data/com.termux/files/usr/lib/libandroid-shmem.so` |

*(เช่นเดียวกันสำหรับสถาปัตยกรรม `x86_64` โดยใช้ไฟล์ `.deb` ของสถาปัตยกรรม `x86_64` หรือ `i686` แทน)*

---

## 3. สคริปต์ฟื้นฟูอัตโนมัติ (Automated Restoration Script)
หากในอนาคตไฟล์ไบนารีเหล่านั้นสูญหาย เสียหาย หรือต้องการอัปเดตเป็นเวอร์ชันใหม่ สามารถนำโค้ดไพทอน (Python) ด้านล่างไปบันทึกเป็นไฟล์ `restore_libs.py` ที่ระดับโฟลเดอร์ Root ของโครงการ แล้วรันคำสั่ง `python3 restore_libs.py` เพื่อดาวน์โหลดและติดตั้งใหม่ให้สมบูรณ์ทันทีโดยอัตโนมัติ:

```python
import os
import urllib.request
import io
import tarfile
import subprocess

MIRROR_BASE = "https://mirrors.ustc.edu.cn/termux/apt/termux-main"

DOWNLOAD_MAPS = [
    # --- ARM64-V8A ---
    {
        "dest": "app/src/main/jniLibs/arm64-v8a/libproot.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.88_aarch64.deb",
        "tar_path": "./data/data/com.termux/files/usr/bin/proot"
    },
    {
        "dest": "app/src/main/jniLibs/arm64-v8a/libproot-loader.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.88_aarch64.deb",
        "tar_path": "./data/data/com.termux/files/usr/libexec/proot/loader"
    },
    {
        "dest": "app/src/main/jniLibs/arm64-v8a/libproot-loader32.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.88_aarch64.deb",
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
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.88_x86_64.deb",
        "tar_path": "./data/data/com.termux/files/usr/bin/proot"
    },
    {
        "dest": "app/src/main/jniLibs/x86_64/libproot-loader.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.88_x86_64.deb",
        "tar_path": "./data/data/com.termux/files/usr/libexec/proot/loader"
    },
    {
        "dest": "app/src/main/jniLibs/x86_64/libproot-loader32.so",
        "url": f"{MIRROR_BASE}/pool/main/p/proot/proot_5.1.107.88_x86_64.deb",
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
```

---

## 4. สรุปความเปลี่ยนแปลงในซอร์สโค้ด (Source Code Integration)
ในคลาส `LinuxTerminalSimulator.kt` เราได้เพิ่มกลไกเพื่อให้ระบบมีความยืดหยุ่นและปลอดภัยจากสถาปัตยกรรมที่คลาดเคลื่อน:
1. **`isHostCpuArm()`**: ฟังก์ชันตรวจสอบสถาปัตยกรรมของ CPU เครื่องผู้ใช้อย่างไดนามิก เพื่อให้เลือกใช้ URL ของ Rootfs ที่เหมาะสมระหว่าง ARM64 และ x86_64 ได้อย่างแม่นยำที่สุด
2. **`isValidElf()`**: ตรวจสอบ Header ของไบนารีก่อนรัน หากตรวจพบว่าไม่ใช่ไฟล์ ELF ที่สมบูรณ์ ระบบจะรายงานปัญหาล่วงหน้าทันทีเพื่อป้องกันการระเบิดของรันไทม์
3. **การเข้ากันได้ของ Distro URL**: เมื่อผู้ใช้ทำการติดตั้ง Distro ระบบจะดึงลิงก์ rootfs ของสถาปัตยกรรมที่แมตช์เข้าคู่กับอุปกรณ์โดยตรง (เช่น Ubuntu Base, Debian, Arch Linux ARM, Alpine, Fedora) เพื่อให้มั่นใจว่าจะไม่มีการเปิด Distro ผิดสถาปัตยกรรมอีกต่อไป

---

## 5. การแก้ไขปัญหารันไทม์และการสแกนสิทธิ์ไฟล์ (PRoot Runtime & Permission Fixes)

หากพบปัญหา **"Permission Denied"**, **"No such file or directory"**, หรือ **"PRoot hanging/crash"** ในระหว่างรันไทม์ ให้ตรวจสอบและคงการตั้งค่าใน `LinuxTerminalSimulator.kt` ดังนี้:

1. **การกำหนดสิทธิ์ Executable แบบ Deep Scan (`ensureBinariesAndShellsExecutable`)**:
   - เพิ่มความลึกในการวนลูปสแกนไดเรกทอรี (`depth > 12`) เพื่อให้เข้าถึงพาธย่อยใน Linux เช่น `lib/aarch64-linux-gnu`, `lib/x86_64-linux-gnu`, `usr/lib64`
   - อนุญาตให้กำหนดสิทธิ์ `+r` และ `+x` แก่ทุกไฟล์ในไดเรกทอรีไบนารีรวมถึง Symbolic Links เพื่อไม่ให้ Link Resolution สะดุด

2. **การตั้งค่า Environment Variables สำหรับ PRoot Loaders (`PROOT_LOADER` & `PROOT_LOADER_32`)**:
   - ตรวจสอบและแมปพาธของ `libproot-loader.so` และ `libproot-loader32.so` เข้ากับ `builder.environment()["PROOT_LOADER"]` และ `PROOT_LOADER_32` ก่อนรัน `ProcessBuilder`

3. **การเคลียร์ไฟล์ Temporary & Stale Locks (`prootTmpDir`)**:
   - ใช้ไดเรกทอรี `File(context.filesDir, "tmp")` สำหรับเก็บไฟล์ชั่วคราวของ PRoot
   - ทำการลบไฟล์ค้างเก่าที่ขึ้นต้นด้วย `proot-` หรือไฟล์ `socket`/`lock` ก่อนเปิดโปรเซสใหม่ทุกครั้ง เพื่อป้องกันอาการค้างหรือ Socket collision
   - ตั้งค่า `PROOT_TMP_DIR`, `PROOT_TMPDIR`, `TMPDIR`, `TEMP`, และ `TMP` ชี้ไปยังไดเรกทอรีชั่วคราวนี้อย่างเป็นเอกภาพ

4. **การแก้ไขปัญหา dpkg Interrupted และ Permission Denied บน `status-old` (`repairDpkgDatabase`)**:
   - **สาเหตุ (Root Cause)**: ในสภาพแวดล้อม PRoot บน Android Host Filesystem เมื่อ `dpkg` ถูกขัดจังหวะ (Interrupted) หรือทำงานแบบ Atomic Rename/Backup ไฟล์ `/var/lib/dpkg/status` ไปยัง `status-old` จะเกิดปัญหา `Permission denied` หากสิทธิ์ระดับระบบ host ของ Android (`setWritable(true, false)`) ไม่ครอบคลุมทั่วถึงทุกไฟล์และโฟลเดอร์ใน `/var/lib/dpkg` หรือกรณี `status-old` มีสิทธิ์เป็น read-only
   - **แนวทางการแก้ไขอัตโนมัติ (Self-Healing Algorithm)**:
     1. **Recursive Grant Permissions (`makeTreeWritable`)**: วนลูปกำหนดสิทธิ์ Read/Write/Execute ย้อนกลับไปทั้งพฤกษ์ไดเรกทอรีใน `/var/lib/dpkg`, `/var/lib/apt`, `/var/cache/apt`, `/tmp`, `/etc/dpkg` เพื่อให้กระบวนการของแอปบน Android มีสิทธิ์อ่านเขียนไฟล์ของ Linux ได้สมบูรณ์
     2. **Smart Database Recovery**: หากพบว่า `/var/lib/dpkg/status` เสียหายหรือมีขนาด 0 Byte แต่ `status-old` ยังสมบูรณ์ ให้คัดลอก `status-old` กลับมาเป็น `status` เพื่อกู้คืนฐานข้อมูลแพ็กเกจ (ไม่ลบ `status-old` ทิ้งส่งเดช)
     3. **Explicit Writable Enforcement**: บังคับให้ `status`, `status-old`, `status-new` และ `available` มีสิทธิ์ Read/Write ชัดเจนเสมอ
     4. **Stale Lock Cleanup**: ลบไฟล์ล็อกค้างทิ้งโดยอัตโนมัติ (`/var/lib/dpkg/lock`, `lock-frontend`, `/var/lib/apt/lists/lock`) ก่อนการรันคำสั่งใดๆ (ห้ามสร้างไฟล์ 0 byte หลอกๆ ไว้อยู่ตลอดเวลา)
     5. **Corrupted Update File Cleanup**: ล้างไฟล์ชั่วคราวที่มีขนาด 0 Byte ใน `/var/lib/dpkg/updates/` เพื่อไม่ให้ `dpkg --configure -a` อ่านสะดุด
     6. **Configuration Flags Enforcement**: เขียนไฟล์ `/etc/dpkg/dpkg.cfg.d/force-unsafe-io` เพื่อเปิดตัวเลือก `force-unsafe-io`, `force-bad-path`, `no-debsig`, `force-overwrite` ลดข้อผิดพลาด fsync และสิทธิ์การเขียนไฟล์ชั่วคราวใน Android container

---

## 6. สรุปปัญหาเชิงลึกทั้งหมดของ PRoot / Termux จากชุมชนระดับโลก (Comprehensive PRoot & Termux Issues Matrix)

จากการรวบรวมข้อมูล Issue และ Bug Report จากชุมชน Termux, PRoot-Distro, UserLAnd, AnLinux และ PRoot GitHub Repository สรุปปัญหาคลาสสิกทั้งหมดและวิธีที่ `LinuxTerminalSimulator.kt` รับมือไว้ดังนี้:

| ปัญหาที่พบในชุมชน (Community Issue) | สาเหตุหลัก (Root Cause) | แนวทางแก้ไขและป้องกันในโค้ด (Implemented Mitigation) |
| :--- | :--- | :--- |
| **1. `dpkg: error creating new backup file status-old: Permission denied`** | Android filesystem จำกัดสิทธิ์ atomic rename/fsync บนไฟล์แบ็กอัป | ใช้ `repairDpkgDatabase()` วนลูป `makeTreeWritable()` ระดับลึก และเขียน `dpkg.cfg.d/force-unsafe-io` เพื่อข้ามการทำ fsync บล็อก |
| **2. `dpkg was interrupted, you must manually run dpkg --configure -a`** | กระบวนการติดตั้งดับกลางค้างล็อกฐานข้อมูลแพ็กเกจ | ระบบจะลบไฟล์ lock ค้างอัตโนมัติ และสั่งรัน `dpkg --configure -a --force-confdef --force-confold` ซ่อมแซมก่อนสั่ง `apt-get` เสมอ |
| **3. `ping: socket: Operation not permitted`** | Android ไม่อนุญาตให้แอป Non-root สร้าง ICMP RAW Socket | ระบบจะดักจับคำสั่ง `ping` และเติมสวิตช์ `-c 4` เพื่อป้องกันกระบวนการค้างอินฟินิตี้โดยใช้นิวไคลเอ็นต์สตรีมแทน |
| **4. `shm_open: No such file or directory` / PostgreSQL / Chrome crash** | Android ไม่มีไดเรกทอรี `/dev/shm` (Shared Memory) | แมปไดเรกทอรีแคช Host `context.cacheDir/shm` เข้ากับ `/dev/shm` ผ่าน bind mount (`-b cache/shm:/dev/shm`) และใส่ `PROOT_NO_SHMEM_WARNING=1` |
| **5. `Could not resolve host` / DNS Resolution Failure** | Rootfs ดั้งเดิมไม่มีไฟลบรรจุ Nameserver สำหรับ Android | `repairDpkgDatabase()` จะตรวจสอบและสร้าง `/etc/resolv.conf` ( Cloudflare `1.1.1.1`, Google `8.8.8.8`) และ `/etc/hosts` ให้อัตโนมัติทุกครั้ง |
| **6. `PRoot process hanging` / Socket Collision** | มีไฟล์ socket หรือ lock ค้างใน `TMPDIR` ของ PRoot | ล้างไฟล์ที่ขึ้นต้นด้วย `proot-` หรือไฟล์ `socket/lock` ใน `context.filesDir/tmp` ก่อนรันคำสั่ง และตั้งค่า `PROOT_NO_SECCOMP=1` |
| **7. Post-install daemon crash (`systemctl` / `start-stop-daemon`)** | PRoot ไม่มี PID 1 PID init/systemd รันอยู่จริง | สร้างไฟล์จำลอง Dummy script สำหรับ `start-stop-daemon`, `systemctl`, `initctl` คืนค่า `exit 0` ป้องกันแพ็กเกจล่มตอนกอบกู้บริการ |
| **8. `locale` warnings / Perl-Python non-ASCII crash** | ค่าตัวแปรภาษาและ Encoding ไม่ได้ถูกกำหนดใน Shell | กำหนด `export LANG=C.UTF-8` และ `LC_ALL=C.UTF-8` ใน Environment ของ PRoot ทุกรอบคำสั่ง |

---
*จัดทำขึ้นเพื่อให้การทำงานในระยะยาวเป็นไปได้อย่างสมบูรณ์และโปร่งใสสูงสุด*
