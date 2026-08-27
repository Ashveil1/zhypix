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
| **9. `exec(.../files/lib/libbusybox.so) permission denied`** | SELinux W^X บล็อกการ execute ไบนารีจาก app internal storage | แยกโหมด Host Shell ไปใช้ `/system/bin/sh` และใช้ Pure JVM Extractor สกัด Rootfs แทนการเรียก Busybox ภายนอก |
| **10. `cannot locate symbol "lbb_main"`** | ไบนารี Busybox ของ Termux เป็น Launcher Stub ที่ต้องการ dynamic library คู่กัน | สกัด Rootfs และ Symlink ในระดับ Java/JVM (`Os.symlink`) โดยตรง ไม่ต้องพึ่งพา Busybox binary |

---

## 7. สถาปัตยกรรม Host Shell vs. Guest Distro และการจัดการ Rootfs อย่างถูกต้อง (Proot-Distro Architecture Standards)

### 7.1 ความแตกต่างระหว่าง Host Shell และ Guest Linux Distro
การทำงานของ Terminal จำลองบน Android แบ่งเป็น 2 ระดับอย่างเด็ดขาด:

```
+-------------------------------------------------------------------------------+
|                             Terminal Screen (UI)                             |
+-------------------------------------------------------------------------------+
        |                                                       |
        v (Host Mode: distro == null)                           v (Guest Mode: distro != null)
+-------------------------------+               +-------------------------------+
|       Host Android Shell      |               |          PRoot Engine         |
|        /system/bin/sh         |               |   nativeLibraryDir/libproot   |
|   (SELinux Allowed, Stable)   |               +-------------------------------+
+-------------------------------+                               | (ptrace syscall translation)
                                                                v
                                                +-------------------------------+
                                                |     Guest Linux Filesystem    |
                                                |  (files/proot-distros/debian) |
                                                |  /bin/bash, /usr/bin/apt, etc.|
                                                +-------------------------------+
```

1. **Host Shell Mode (Android Native OS)**:
   - **ข้อกำหนดตายตัว**: ต้องรัน `/system/bin/sh` (ซึ่งเป็น Standard Toybox/Mksh ของ Android)
   - **ข้อห้ามสำคัญ**: **ห้าม** พยายามรัน Busybox หรือ binary อื่นๆ จากโฟลเดอร์ `context.filesDir` โดยตรง เนื่องจาก Android 10+ (API 29+) มีกฎ **SELinux W^X (Write XOR Execute)** บล็อกไม่ให้กระบวนการของแอปรัน Executable ที่วางอยู่ใน Storage ของตนเอง ส่งผลให้เกิดข้อผิดพลาด `exec() permission denied`
   - `/system/bin/sh` ได้รับอนุญาตจากระบบปฏิบัติการอย่างถูกต้อง จึงสามารถรันคำสั่งพื้นฐาน (เช่น `ls`, `ps`, `cat`, `echo`, `logcat`) ได้อย่างสมบูรณ์โดยไม่สะดุดปัญหาเรื่องสิทธิ์

2. **Guest Distro Mode (PRoot Sandbox)**:
   - ตัวโปรแกรม `libproot.so` จะถูกติดตั้งและรันจาก `context.applicationInfo.nativeLibraryDir` (ผ่านการตั้งค่า `extractNativeLibs = true` ใน Gradle) ซึ่งเป็นไดเรกทอรี Native Libs ที่ Android อนุญาตให้ Execute ได้
   - PRoot ใช้เทคนิค `ptrace(2)` เพื่อดักจับ System Calls ของไบนารีใน Guest OS (เช่น `/bin/bash`, `apt`, `python3`) โดยที่ระบบ Android ไม่ได้รับรู้ว่าเป็นการรันโปรแกรมภายนอก จึงสามารถรัน Linux Environment ได้เต็มรูปแบบโดยไม่ต้องรูทเครื่อง (No Root Required)

---

### 7.2 โครงสร้างของ Termux BusyBox และทำไมจึงไม่ควรนำมารันตรงๆ
- แพ็กเกจ Busybox ของ Termux (`busybox_...deb`) มีการแบ่งโค้ดออกเป็น 2 ส่วน:
  1. **Launcher Stub** (`/usr/bin/busybox` ขนาด ~4 KB): ทำหน้าที่เป็นตัวเรียกฟังก์ชัน `lbb_main(...)`
  2. **Shared Object Library** (`/usr/lib/libbusybox.so.1.38.0` ขนาด ~870 KB): บรรจุคำสั่ง Unix ทั้งหมดและ Export สัญลักษณ์ `lbb_main`
- หากนำเฉพาะไฟล์ Stub มาเปลี่ยนชื่อเป็น `.so` แล้วพยายามเรียกทำงาน จะเกิดข้อผิดพลาด **`cannot locate symbol "lbb_main"`** ทันทีหากไม่มีการลิงก์ Dynamic Library ที่ถูกต้อง และติดเงื่อนไข SELinux W^X ดังกล่าวข้างต้น

---

### 7.3 การแตกไฟล์ Rootfs และจัดการ Symbolic Links แบบ Pure JVM (JVM-First Extractor)
เพื่อตัดปัญหาความพึ่งพาของไบนารีภายนอกและหลีกเลี่ยงข้อผิดพลาดเรื่อง Permission ทั้งหมด การดาวน์โหลดและติดตั้ง Distro Rootfs (`.tar.gz` หรือ `.tar.xz`) จะต้องทำงานบน JVM 100%:

1. **การคลายการบีบอัด In-Memory**:
   - ใช้ `Apache Commons Compress` (`XZCompressorInputStream` สำหรับ `.xz` และ `GzipCompressorInputStream` สำหรับ `.gz`) ต่อร่วมกับ `TarArchiveInputStream`
   - คลายไฟล์ลงในไดเรกทอรี `context.filesDir/proot-distros/<distro_alias>` โดยตรง
2. **การสร้าง Symbolic Links & Hard Links**:
   - **ห้ามเรียก**: `Runtime.getRuntime().exec(arrayOf("ln", "-s", ...))` เพราะต้องอาศัยคำสั่งภายนอกและเสี่ยงต่อการถูกบล็อกสิทธิ์
   - **ให้ใช้ System Call API โดยตรง**:
     ```kotlin
     // 1. ลองใช้ C-level symlink ผ่าน Android POSIX API (ทำงานได้ตั้งแต่ Android 5.0+)
     android.system.Os.symlink(relativeTarget, symlinkFile.absolutePath)
     
     // 2. หรือใช้ Java NIO API (Android 8.0+)
     java.nio.file.Files.createSymbolicLink(symlinkFile.toPath(), java.nio.file.Paths.get(relativeTarget))
     ```
3. **การแปลง Symlink Target ให้เป็น Relative Path เสมอ**:
   - หาก Link Target ใน Rootfs อ้างอิงเป็น Absolute Path (เช่น `/usr/bin/python3`) ต้องคำนวณ Path สัมพัทธ์เทียบกับตำแหน่งไดเรกทอรีของ Symlink นั้นๆ ก่อนสร้าง Link เพื่อให้ PRoot สามารถเข้าถึงไฟล์ปลายทางได้อย่างถูกต้องแม้ Rootfs จะถูกวางอยู่ใต้โฟลเดอร์ของแอป

---

### 7.4 มาตรฐานการตั้งค่าสภาพแวดล้อมเพื่อเปิด Distro (Standard PRoot Bindings & Env)

เมื่อเริ่ม PTY Session หรือ Execute คำสั่งผ่าน PRoot ให้ปฏิบัติตามมาตรฐาน Arguments ต่อไปนี้เสมอ:

```kotlin
val prootArgs = mutableListOf(
    prootBinaryPath,
    "--kill-on-exit",
    "--link2symlink",
    "-0",                     // จำลองสิทธิ์ Fake Root (uid=0, gid=0)
    "-r", distroDir.absolutePath, // กำหนด Root Directory ของ Guest OS
    "-b", "/dev",
    "-b", "/proc",
    "-b", "/sys",
    "-b", "/dev/urandom:/dev/random",
    "-b", "${context.cacheDir.absolutePath}/shm:/dev/shm", // จำลอง POSIX Shared Memory
    "-w", "/root"             // กำหนด Working Directory
)
```

**Environment Variables ที่จำเป็น:**
- `HOME=/root`
- `USER=root`
- `SHELL=/bin/bash`
- `TERM=xterm-256color`
- `LANG=C.UTF-8` และ `LC_ALL=C.UTF-8`
- `TMPDIR=/tmp`
- `PROOT_LOADER` และ `PROOT_LOADER_32` ชี้ไปยัง loader `.so` ใน `nativeLibraryDir`

---

### 7.5 การแก้ไขและป้องกันข้อผิดพลาด `is not regular file` ใน PRoot

**สาเหตุของปัญหา:**
1. **Rootfs ยังไม่สมบูรณ์หรือไม่มีอยู่จริง (Non-existent / Half-extracted rootfs):** เมื่อมีการเรียก `proot -r <path>` ไปยังไดเรกทอรีที่เพิ่งถูกสร้างด้วย `mkdirs()` แต่ยังดาวน์โหลด/แตกไฟล์ rootfs ไม่เสร็จ หรือไฟล์สูญหาย PRoot จะไม่สามารถตรวจพบโครงสร้าง rootfs และจะรายงานข้อผิดพลาดว่า path นั้นไม่ใช่ regular file / directory ที่ถูกต้อง
2. **ความไม่สอดคล้องของชื่อ Path (`proot-distro` vs `proot-distros`):** ในบางสคริปต์หรือคำสั่งที่ผู้ใช้พิมพ์ มีการอ้างอิง path `files/proot-distro/<alias>` แทน `files/proot-distros/<alias>`
3. **การพยายามเข้า Guest Mode ขณะที่ระบบยังไม่ได้ติดตั้ง Distro:** PTY หรือ Terminal Engine พยายามผูกคำสั่งเข้า PRoot ทันทีที่มีการระบุ Distro Alias โดยไม่ได้ตรวจสอบความสมบูรณ์ของ Rootfs ล่วงหน้า
4. **การตัดทอน `argv[0]` ใน PTY Launch Spec (`buildPtyLaunchSpec` Mismatch):** ใน Termux JNI Subprocess (`termux.c`) อาศัยอาร์เรย์ `argv[]` ที่มีองค์ประกอบแรกเป็นชื่อโปรแกรม (`proot`) หากส่งเฉพาะ `subList(1, size)` จะทำให้อาร์กิวเมนต์ตัวแรกกลายเป็น `-r` และตัวที่สองกลายเป็นโฟลเดอร์ rootfs (`/data/user/0/.../ubuntu`) PRoot จึงเข้าใจผิดว่าโฟลเดอร์ `ubuntu` คือคำสั่งโปรแกรมเชลล์ภายใน Container และรายงานข้อผิดพลาดว่า `'ubuntu' is not a regular file`
5. **Broken Symlinks จากโครงสร้าง Linux UsrMerge ยุคใหม่:** การตัดย่อ Absolute Symlinks ตอนแตกไฟล์ tar ล้มเหลวเนื่องจากการแมป Relative Path ไม่ลึกพอ ทำให้ `/usr/bin/sh` หรือ `/bin/sh` กลายเป็น Broken Symlink ชี้ไปยังไฟล์ที่ไม่มีอยู่จริง

**แนวทางแก้ไขและป้องกันอย่างยั่งยืนใน Zhypix:**
1. **`isDistroFullyInstalled(alias)` Validation Layer:**
   - ตรวจสอบความพร้อมของ Rootfs ล่วงหน้าเสมอ โดยเช็คว่ามีไดเรกทอรี `bin/` หรือ `usr/bin/`, ไฟล์เชลล์ (`bash`, `sh`, `dash`, `ash`), และโครงสร้าง `etc/` ครบถ้วน
2. **Graceful Fallback to Host Shell:**
   - หาก Distro ที่ระบุยังติดตั้งไม่เสร็จหรือไม่สมบูรณ์ ระบบ PTY และ `executeCommand` จะสลับกลับมาใช้ **Host Shell (`/system/bin/sh`)** อย่างปลอดภัย พร้อมแจ้งเตือนให้ผู้ใช้รัน `proot-distro install <alias>` ก่อนใช้งาน
3. **Path Compatibility Symlink & `resolveDistroDir`:**
   - สร้าง Symlink อัตโนมัติระหว่าง `proot-distro` และ `proot-distros` ใน `initialize()` เพื่อให้ทุกคำสั่งทั้งแบบเอกพจน์และพหูพจน์ทำงานร่วมกันได้อย่างราบรื่น 100%
4. **PTY Subprocess `argv[0]` Alignment:**
   - ใน `buildPtyLaunchSpec()` ต้องส่ง `args = cmdList` โดยคงองค์ประกอบแรก (`proot`) ไว้ เพื่อให้ JNI Native Layer (`termux.c`) เติม `argv[0]` ตรงตามมาตรฐาน C Exec-family
5. **Atomic Relative Symlink Creation & Safe Extraction:**
   - คำนวณ Relative Path จากตำแหน่งโฟลเดอร์แม่ของ Symlink ย้อนกลับไปไฟล์ปลายทาง และสร้าง Symlink ชั่วคราว (`.symlink_tmp_*`) ก่อนสั่ง Atomic Rename เพื่อป้องกันการสูญหายของ Symlink ระหว่างกระบวนการสแกนแก้ไข

---
*จัดทำขึ้นเพื่อให้การทำงานในระยะยาวเป็นไปได้อย่างสมบูรณ์และโปร่งใสสูงสุด*
