"""LapDrive server: share one folder of this laptop with paired phones over HTTPS.

Security model:
- The server uses HTTPS only. It makes a self-signed certificate on the first run.
  The phone pins the SHA-256 fingerprint of that certificate, so a different
  server cannot pretend to be this laptop.
- A phone gets access only through pairing. Pairing works only while you run the
  server with --pair. The server shows a one-time code in a QR code. The code
  expires after a time limit and after too many wrong attempts.
- Each paired phone gets a random 256-bit token. The server keeps only the
  SHA-256 hash of the token. You can revoke a phone at any time.
- All paths stay inside the shared root folder. The server resolves symlinks and
  rejects any path that goes outside the root.
"""

from __future__ import annotations

import argparse
import asyncio
import datetime as dt
import hashlib
import hmac
import ipaddress
import math
import json
import mimetypes
import os
import secrets
import shutil
import socket
import stat
import sys
import threading
import time
import urllib.parse
from collections import defaultdict, deque
from pathlib import Path

from fastapi import Depends, FastAPI, HTTPException, Request
from fastapi.responses import FileResponse, JSONResponse, Response
from pydantic import BaseModel, Field

VERSION = "1.0.0"
CONFIG_DIR = Path(os.environ.get("LAPDRIVE_CONFIG", Path.home() / ".config" / "lapdrive"))
CACHE_DIR = Path(os.environ.get("LAPDRIVE_CACHE", Path.home() / ".cache" / "lapdrive"))
CERT_FILE = CONFIG_DIR / "cert.pem"
KEY_FILE = CONFIG_DIR / "key.pem"
STATE_FILE = CONFIG_DIR / "devices.json"

PAIR_CODE_ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789"  # no 0/O, 1/I/L
PAIR_CODE_LEN = 10
PAIR_MAX_ATTEMPTS = 5
AUTH_FAIL_LIMIT = 20  # failures per IP in AUTH_FAIL_WINDOW before a block
AUTH_FAIL_WINDOW = 600
AUTH_BLOCK_SECONDS = 900
THUMB_MAX_SOURCE_BYTES = 60 * 1024 * 1024
THUMB_SIZE = 320
UPLOAD_CHUNK = 2 * 1024 * 1024
UPLOAD_IDLE_TIMEOUT = 60  # seconds with no data before the server stops an upload request
UPLOAD_SESSION_TTL = 24 * 3600  # remove unfinished uploads after a day


# --------------------------------------------------------------------------- files


def _secure_write(path: Path, data: bytes) -> None:
    """Write a file with mode 0600 through a temp file and an atomic rename."""
    path.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
    tmp = path.with_suffix(path.suffix + ".tmp")
    fd = os.open(tmp, os.O_WRONLY | os.O_CREAT | os.O_TRUNC, 0o600)
    with os.fdopen(fd, "wb") as f:
        f.write(data)
        f.flush()
        os.fsync(f.fileno())
    os.replace(tmp, path)


def ensure_config_dir() -> None:
    CONFIG_DIR.mkdir(parents=True, exist_ok=True, mode=0o700)
    os.chmod(CONFIG_DIR, 0o700)


# --------------------------------------------------------------------------- TLS


def local_addresses() -> list[str]:
    """Return the IPv4 addresses of this machine that a phone can reach."""
    addrs: list[str] = []
    try:
        import subprocess

        out = subprocess.run(["ip", "-4", "-o", "addr"], capture_output=True, text=True, timeout=3).stdout
        for line in out.splitlines():
            parts = line.split()
            if "inet" in parts:
                ip = parts[parts.index("inet") + 1].split("/")[0]
                if not ip.startswith("127."):
                    addrs.append(ip)
    except Exception:
        pass
    if not addrs:
        try:
            with socket.socket(socket.AF_INET, socket.SOCK_DGRAM) as s:
                s.connect(("10.255.255.255", 1))
                addrs.append(s.getsockname()[0])
        except Exception:
            pass

    def rank(ip: str) -> int:
        # LAN addresses first, then Tailscale/CGNAT (100.64.0.0/10), then others.
        a = ipaddress.ip_address(ip)
        if a.is_private and not ip.startswith("172.17."):
            return 0
        if a in ipaddress.ip_network("100.64.0.0/10"):
            return 1
        return 2

    return sorted(dict.fromkeys(addrs), key=rank)


def ensure_certificate() -> None:
    if CERT_FILE.exists() and KEY_FILE.exists():
        return
    from cryptography import x509
    from cryptography.hazmat.primitives import hashes, serialization
    from cryptography.hazmat.primitives.asymmetric import ec
    from cryptography.x509.oid import NameOID

    key = ec.generate_private_key(ec.SECP256R1())
    name = x509.Name([x509.NameAttribute(NameOID.COMMON_NAME, f"LapDrive {socket.gethostname()}")])
    san: list[x509.GeneralName] = [x509.DNSName("localhost"), x509.DNSName(socket.gethostname())]
    for ip in local_addresses() + ["127.0.0.1"]:
        san.append(x509.IPAddress(ipaddress.ip_address(ip)))
    now = dt.datetime.now(dt.timezone.utc)
    cert = (
        x509.CertificateBuilder()
        .subject_name(name)
        .issuer_name(name)
        .public_key(key.public_key())
        .serial_number(x509.random_serial_number())
        .not_valid_before(now - dt.timedelta(days=1))
        .not_valid_after(now + dt.timedelta(days=3650))
        .add_extension(x509.SubjectAlternativeName(san), critical=False)
        .add_extension(x509.BasicConstraints(ca=False, path_length=None), critical=True)
        .sign(key, hashes.SHA256())
    )
    ensure_config_dir()
    _secure_write(
        KEY_FILE,
        key.private_bytes(
            serialization.Encoding.PEM,
            serialization.PrivateFormat.PKCS8,
            serialization.NoEncryption(),
        ),
    )
    _secure_write(CERT_FILE, cert.public_bytes(serialization.Encoding.PEM))
    print(f"Created a new TLS certificate in {CERT_FILE}")


def cert_fingerprint() -> str:
    from cryptography import x509
    from cryptography.hazmat.primitives import serialization

    cert = x509.load_pem_x509_certificate(CERT_FILE.read_bytes())
    return hashlib.sha256(cert.public_bytes(serialization.Encoding.DER)).hexdigest()


# --------------------------------------------------------------------------- devices


def _hash_token(token: str) -> str:
    return hashlib.sha256(token.encode()).hexdigest()


class DeviceStore:
    """Paired devices in devices.json. The server reloads the file when it changes,
    so `lapdrive revoke` takes effect on a running server."""

    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._mtime = -1.0
        self._devices: list[dict] = []
        self._dirty_seen: dict[str, float] = {}
        self._last_flush = 0.0

    def _reload_if_changed(self) -> None:
        try:
            m = STATE_FILE.stat().st_mtime
        except FileNotFoundError:
            self._devices, self._mtime = [], -1.0
            return
        if m != self._mtime:
            self._devices = json.loads(STATE_FILE.read_text() or "{}").get("devices", [])
            self._mtime = m

    def _save(self) -> None:
        _secure_write(STATE_FILE, json.dumps({"devices": self._devices}, indent=2).encode())
        self._mtime = STATE_FILE.stat().st_mtime

    def list(self) -> list[dict]:
        with self._lock:
            self._reload_if_changed()
            return [dict(d) for d in self._devices]

    def add(self, name: str) -> tuple[str, str]:
        token = secrets.token_urlsafe(32)
        dev_id = secrets.token_hex(4)
        with self._lock:
            self._reload_if_changed()
            self._devices.append(
                {
                    "id": dev_id,
                    "name": name,
                    "token_sha256": _hash_token(token),
                    "paired_at": int(time.time()),
                    "last_seen": int(time.time()),
                }
            )
            self._save()
        return dev_id, token

    def revoke(self, dev_id: str) -> bool:
        with self._lock:
            self._reload_if_changed()
            before = len(self._devices)
            self._devices = [d for d in self._devices if d["id"] != dev_id]
            if len(self._devices) == before:
                return False
            self._save()
            return True

    def authenticate(self, token: str) -> dict | None:
        h = _hash_token(token)
        with self._lock:
            self._reload_if_changed()
            found = None
            for d in self._devices:
                # Compare every entry in constant time.
                if hmac.compare_digest(d["token_sha256"], h):
                    found = d
            if found is not None:
                now = time.time()
                found["last_seen"] = int(now)
                if now - self._last_flush > 60:
                    self._last_flush = now
                    self._save()
            return dict(found) if found else None


# --------------------------------------------------------------------------- pairing


class Pairing:
    def __init__(self, ttl_seconds: int) -> None:
        self.code: str | None = "".join(secrets.choice(PAIR_CODE_ALPHABET) for _ in range(PAIR_CODE_LEN))
        self.expires = time.time() + ttl_seconds
        self.attempts = 0
        self.lock = threading.Lock()

    def check(self, code: str) -> bool:
        with self.lock:
            if self.code is None or time.time() > self.expires:
                self.code = None
                raise HTTPException(403, "Pairing is not active. Start the server with --pair.")
            self.attempts += 1
            ok = hmac.compare_digest(self.code.encode(), code.strip().upper().encode())
            if ok:
                self.code = None  # one-time code
                return True
            if self.attempts >= PAIR_MAX_ATTEMPTS:
                self.code = None
                print("Pairing disabled: too many wrong codes.", flush=True)
            return False


# --------------------------------------------------------------------------- rate limit


class FailLimiter:
    def __init__(self) -> None:
        self.fails: dict[str, deque] = defaultdict(deque)
        self.blocked: dict[str, float] = {}
        self.lock = threading.Lock()

    def check(self, ip: str) -> None:
        with self.lock:
            until = self.blocked.get(ip)
            if until and until > time.time():
                raise HTTPException(429, "Too many failed attempts. Try again later.")

    def fail(self, ip: str) -> None:
        now = time.time()
        with self.lock:
            q = self.fails[ip]
            q.append(now)
            while q and q[0] < now - AUTH_FAIL_WINDOW:
                q.popleft()
            if len(q) >= AUTH_FAIL_LIMIT:
                self.blocked[ip] = now + AUTH_BLOCK_SECONDS
                q.clear()
                print(f"Blocked {ip} for {AUTH_BLOCK_SECONDS}s after repeated auth failures.", flush=True)


# --------------------------------------------------------------------------- app


class PairRequest(BaseModel):
    code: str = Field(min_length=4, max_length=64)
    device_name: str = Field(min_length=1, max_length=64)


class UploadStart(BaseModel):
    dir: str = Field(default="", max_length=4096)
    name: str = Field(min_length=1, max_length=255)
    size: int = Field(ge=0)


class PathRequest(BaseModel):
    path: str = Field(max_length=4096)


class RenameRequest(BaseModel):
    path: str = Field(max_length=4096)
    new_name: str = Field(min_length=1, max_length=255)


async def body_chunks(request: Request, idle_seconds: float = UPLOAD_IDLE_TIMEOUT):
    """Yield the request body. Stop the request when no data arrives for idle_seconds.
    A mobile connection can stall and stay open. This frees the request."""
    it = request.stream().__aiter__()
    while True:
        try:
            chunk = await asyncio.wait_for(it.__anext__(), idle_seconds)
        except StopAsyncIteration:
            return
        except asyncio.TimeoutError:
            raise HTTPException(408, "Upload stalled")
        yield chunk


def safe_name(name: str) -> str:
    name = name.strip()
    if (
        not name
        or name in (".", "..")
        or "/" in name
        or "\\" in name
        or "\x00" in name
        or len(name.encode()) > 255
    ):
        raise HTTPException(400, "Invalid file name")
    return name


def create_app(root: Path, read_only: bool, show_hidden: bool, max_upload: int, pairing: Pairing | None) -> FastAPI:
    root = root.resolve()
    devices = DeviceStore()
    limiter = FailLimiter()
    app = FastAPI(docs_url=None, redoc_url=None, openapi_url=None)

    def client_ip(request: Request) -> str:
        return request.client.host if request.client else "?"

    def require_device(request: Request) -> dict:
        ip = client_ip(request)
        limiter.check(ip)
        auth = request.headers.get("authorization", "")
        if not auth.lower().startswith("bearer "):
            limiter.fail(ip)
            raise HTTPException(401, "Missing token")
        dev = devices.authenticate(auth[7:].strip())
        if dev is None:
            limiter.fail(ip)
            raise HTTPException(401, "Invalid or revoked token")
        return dev

    def require_write() -> None:
        if read_only:
            raise HTTPException(403, "The server is in read-only mode")

    def resolve(rel: str, must_exist: bool = True) -> Path:
        rel = (rel or "").replace("\\", "/").strip().lstrip("/")
        if "\x00" in rel:
            raise HTTPException(400, "Invalid path")
        p = (root / rel).resolve()
        if p != root and root not in p.parents:
            raise HTTPException(403, "Path is outside the shared folder")
        if not show_hidden and any(part.startswith(".") for part in p.relative_to(root).parts):
            raise HTTPException(404, "Not found")
        if must_exist and not p.exists():
            raise HTTPException(404, "Not found")
        return p

    def rel_of(p: Path) -> str:
        r = p.relative_to(root).as_posix()
        return "" if r == "." else r

    def entry(p: Path) -> dict | None:
        try:
            st = p.stat()
            real = p.resolve()
        except OSError:
            return None
        if real != root and root not in real.parents:
            return None  # a symlink that points outside the root
        is_dir = stat.S_ISDIR(st.st_mode)
        return {
            "name": p.name,
            "path": rel_of(p),
            "is_dir": is_dir,
            "size": 0 if is_dir else st.st_size,
            "mtime": int(st.st_mtime),
            "mime": None if is_dir else (mimetypes.guess_type(p.name)[0] or "application/octet-stream"),
        }

    @app.middleware("http")
    async def security_headers(request: Request, call_next):
        resp: Response = await call_next(request)
        resp.headers["Cache-Control"] = resp.headers.get("Cache-Control", "no-store")
        resp.headers["X-Content-Type-Options"] = "nosniff"
        resp.headers["Strict-Transport-Security"] = "max-age=31536000"
        return resp

    @app.post("/api/pair")
    def pair(body: PairRequest, request: Request):
        ip = client_ip(request)
        limiter.check(ip)
        if pairing is None:
            raise HTTPException(403, "Pairing is not active. Start the server with --pair.")
        if not pairing.check(body.code):
            limiter.fail(ip)
            raise HTTPException(401, "Wrong pairing code")
        dev_id, token = devices.add(body.device_name.strip())
        print(f"Paired new device '{body.device_name}' (id {dev_id}) from {ip}", flush=True)
        return {"token": token, "device_id": dev_id, "server_name": socket.gethostname()}

    @app.get("/api/info")
    def info(dev: dict = Depends(require_device)):
        usage = shutil.disk_usage(root)
        return {
            "server_name": socket.gethostname(),
            "root_name": root.name or str(root),
            "read_only": read_only,
            "version": VERSION,
            "device_name": dev["name"],
            "disk_free": usage.free,
            "disk_total": usage.total,
            "max_upload": max_upload,
        }

    @app.get("/api/list")
    def list_dir(path: str = "", _: dict = Depends(require_device)):
        d = resolve(path)
        if not d.is_dir():
            raise HTTPException(400, "Not a folder")
        items = []
        try:
            with os.scandir(d) as it:
                for de in it:
                    if not show_hidden and de.name.startswith("."):
                        continue
                    e = entry(Path(de.path))
                    if e:
                        items.append(e)
        except PermissionError:
            raise HTTPException(403, "Permission denied")
        items.sort(key=lambda e: (not e["is_dir"], e["name"].lower()))
        return {"path": rel_of(d), "items": items}

    @app.get("/api/search")
    def search(q: str, path: str = "", _: dict = Depends(require_device)):
        q = q.strip().lower()
        if len(q) < 2:
            raise HTTPException(400, "Query is too short")
        base = resolve(path)
        found: list[dict] = []
        deadline = time.time() + 5
        for dirpath, dirnames, filenames in os.walk(base):
            if not show_hidden:
                dirnames[:] = [n for n in dirnames if not n.startswith(".")]
                filenames = [n for n in filenames if not n.startswith(".")]
            for n in dirnames + filenames:
                if q in n.lower():
                    e = entry(Path(dirpath) / n)
                    if e:
                        found.append(e)
                if len(found) >= 200 or time.time() > deadline:
                    return {"items": found, "truncated": True}
        return {"items": found, "truncated": False}

    @app.get("/api/file")
    def get_file(path: str, download: bool = False, _: dict = Depends(require_device)):
        p = resolve(path)
        if not p.is_file():
            raise HTTPException(400, "Not a file")
        mime = mimetypes.guess_type(p.name)[0] or "application/octet-stream"
        # FileResponse supports HTTP Range requests, so the phone can seek in video.
        return FileResponse(
            p,
            media_type=mime,
            filename=p.name,
            content_disposition_type="attachment" if download else "inline",
            headers={"Cache-Control": "no-store"},
        )

    @app.get("/api/thumb")
    def thumb(path: str, _: dict = Depends(require_device)):
        p = resolve(path)
        if not p.is_file():
            raise HTTPException(400, "Not a file")
        st = p.stat()
        if st.st_size > THUMB_MAX_SOURCE_BYTES:
            raise HTTPException(415, "File is too large for a thumbnail")
        key = hashlib.sha256(f"{p}|{st.st_mtime_ns}|{st.st_size}".encode()).hexdigest()
        cached = CACHE_DIR / "thumbs" / f"{key}.jpg"
        if not cached.exists():
            try:
                from PIL import Image, ImageOps

                with Image.open(p) as im:
                    im = ImageOps.exif_transpose(im)
                    im.thumbnail((THUMB_SIZE, THUMB_SIZE))
                    if im.mode not in ("RGB", "L"):
                        im = im.convert("RGB")
                    cached.parent.mkdir(parents=True, exist_ok=True, mode=0o700)
                    tmp = cached.with_suffix(".tmp")
                    im.save(tmp, "JPEG", quality=80)
                    os.replace(tmp, cached)
            except Exception:
                raise HTTPException(415, "No thumbnail for this file")
        return FileResponse(cached, media_type="image/jpeg", headers={"Cache-Control": "private, max-age=86400"})

    def final_target(target_dir: Path, name: str, overwrite: bool) -> Path:
        final = target_dir / name
        if final.exists() and not overwrite:
            stem, suffix = os.path.splitext(name)
            n = 1
            while (target_dir / f"{stem} ({n}){suffix}").exists():
                n += 1
            final = target_dir / f"{stem} ({n}){suffix}"
        if final.exists() and final.is_dir():
            raise HTTPException(409, "A folder with this name exists")
        return final

    def check_upload_target(dir_rel: str, name: str, size: int | None) -> tuple[Path, str]:
        target_dir = resolve(dir_rel)
        if not target_dir.is_dir():
            raise HTTPException(400, "Not a folder")
        name = safe_name(name)
        if not show_hidden and name.startswith("."):
            raise HTTPException(400, "Hidden file names are not allowed")
        if size is not None:
            if size > max_upload:
                raise HTTPException(413, "File is too large")
            if shutil.disk_usage(target_dir).free < size + 64 * 1024 * 1024:
                raise HTTPException(507, "Not enough free space on the laptop")
        return target_dir, name

    # Single-request upload. The app uses it only when it cannot read the file in pieces.
    @app.put("/api/upload")
    async def upload(
        request: Request,
        dir: str = "",
        name: str = "",
        overwrite: bool = False,
        _: dict = Depends(require_device),
    ):
        require_write()
        declared = request.headers.get("content-length")
        target_dir, name = check_upload_target(dir, name, int(declared) if declared and declared.isdigit() else None)
        part = target_dir / f".lapdrive-{secrets.token_hex(8)}.part"
        written = 0
        try:
            fd = os.open(part, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
            with os.fdopen(fd, "wb") as f:
                async for chunk in body_chunks(request):
                    written += len(chunk)
                    if written > max_upload:
                        raise HTTPException(413, "File is too large")
                    f.write(chunk)
            final = final_target(target_dir, name, overwrite)
            os.replace(part, final)
        except BaseException:
            part.unlink(missing_ok=True)
            raise
        return {"saved_as": rel_of(final), "size": written}

    # Resumable upload in pieces. The phone sends pieces in parallel and sends a
    # failed piece again. This keeps uploads usable on slow or unstable mobile networks.
    uploads: dict[str, dict] = {}
    uploads_lock = threading.Lock()

    def cleanup_uploads() -> None:
        now = time.time()
        with uploads_lock:
            for uid, s in list(uploads.items()):
                if now - s["touched"] > UPLOAD_SESSION_TTL:
                    s["part"].unlink(missing_ok=True)
                    del uploads[uid]

    def get_session(uid: str, dev: dict) -> dict:
        with uploads_lock:
            s = uploads.get(uid)
        if s is None or s["device"] != dev["id"]:
            raise HTTPException(404, "Upload not found. Start it again.")
        return s

    @app.post("/api/uploads")
    def upload_start(body: UploadStart, dev: dict = Depends(require_device)):
        require_write()
        cleanup_uploads()
        target_dir, name = check_upload_target(body.dir, body.name, body.size)
        uid = secrets.token_hex(16)
        part = target_dir / f".lapdrive-{uid}.part"
        fd = os.open(part, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        try:
            os.ftruncate(fd, body.size)
        finally:
            os.close(fd)
        n = max(1, math.ceil(body.size / UPLOAD_CHUNK))
        with uploads_lock:
            uploads[uid] = {
                "device": dev["id"], "dir": target_dir, "name": name, "size": body.size,
                "part": part, "n": n, "done": set(), "touched": time.time(),
            }
        return {"id": uid, "chunk_size": UPLOAD_CHUNK, "chunks": n}

    @app.get("/api/uploads/{uid}")
    def upload_status(uid: str, dev: dict = Depends(require_device)):
        s = get_session(uid, dev)
        return {"received": sorted(s["done"]), "chunks": s["n"]}

    @app.put("/api/uploads/{uid}/{index}")
    async def upload_chunk(uid: str, index: int, request: Request, dev: dict = Depends(require_device)):
        s = get_session(uid, dev)
        if not 0 <= index < s["n"]:
            raise HTTPException(400, "Bad piece number")
        start = index * UPLOAD_CHUNK
        expected = min(UPLOAD_CHUNK, s["size"] - start)
        buf = bytearray()
        async for c in body_chunks(request):
            buf += c
            if len(buf) > expected:
                raise HTTPException(400, "Piece is too large")
        if len(buf) != expected:
            raise HTTPException(400, "Piece is incomplete")
        fd = os.open(s["part"], os.O_WRONLY)
        try:
            os.pwrite(fd, buf, start)
        finally:
            os.close(fd)
        with uploads_lock:
            s["done"].add(index)
            s["touched"] = time.time()
        return {"ok": True}

    @app.post("/api/uploads/{uid}/finish")
    def upload_finish(uid: str, dev: dict = Depends(require_device)):
        s = get_session(uid, dev)
        missing = s["n"] - len(s["done"])
        if missing:
            raise HTTPException(409, f"{missing} piece(s) are missing")
        with open(s["part"], "rb+") as f:
            os.fsync(f.fileno())
        final = final_target(s["dir"], s["name"], False)
        os.replace(s["part"], final)
        with uploads_lock:
            uploads.pop(uid, None)
        return {"saved_as": rel_of(final), "size": s["size"]}

    @app.delete("/api/uploads/{uid}")
    def upload_cancel(uid: str, dev: dict = Depends(require_device)):
        s = get_session(uid, dev)
        s["part"].unlink(missing_ok=True)
        with uploads_lock:
            uploads.pop(uid, None)
        return {"ok": True}

    @app.post("/api/mkdir")
    def mkdir(body: PathRequest, _: dict = Depends(require_device)):
        require_write()
        parent_rel, _, leaf = body.path.strip("/").rpartition("/")
        parent = resolve(parent_rel)
        p = parent / safe_name(leaf)
        resolve(rel_of(p), must_exist=False)
        try:
            p.mkdir()
        except FileExistsError:
            raise HTTPException(409, "Already exists")
        return {"path": rel_of(p)}

    @app.post("/api/rename")
    def rename(body: RenameRequest, _: dict = Depends(require_device)):
        require_write()
        p = resolve(body.path)
        if p == root:
            raise HTTPException(400, "You cannot rename the shared folder")
        target = p.parent / safe_name(body.new_name)
        resolve(rel_of(target), must_exist=False)
        if target.exists():
            raise HTTPException(409, "Already exists")
        p.rename(target)
        return {"path": rel_of(target)}

    @app.post("/api/delete")
    def delete(body: PathRequest, _: dict = Depends(require_device)):
        require_write()
        p = resolve(body.path)
        if p == root:
            raise HTTPException(400, "You cannot delete the shared folder")
        from send2trash import send2trash

        send2trash(str(p))  # move to the laptop trash, not a permanent delete
        return {"trashed": rel_of(p)}

    @app.exception_handler(PermissionError)
    async def perm_handler(_: Request, __: PermissionError):
        return JSONResponse({"detail": "Permission denied"}, status_code=403)

    return app


# --------------------------------------------------------------------------- CLI


def print_pairing(port: int, pairing: Pairing, ttl: int) -> None:
    import qrcode

    hosts = local_addresses()
    query = urllib.parse.urlencode(
        {"h": ",".join(hosts), "p": port, "fp": cert_fingerprint(), "c": pairing.code}
    )
    link = f"lapdrive://pair?{query}"
    qr = qrcode.QRCode(border=1, error_correction=qrcode.constants.ERROR_CORRECT_L)
    qr.add_data(link)
    print("\nScan this QR code with the LapDrive app to pair your phone:\n")
    qr.print_ascii(invert=True)
    print(f"\nOr paste this link in the app:\n  {link}\n")
    print(f"Pairing code: {pairing.code}  (expires in {ttl // 60} min, one use)")
    print(f"Certificate SHA-256: {cert_fingerprint()}\n", flush=True)


def cmd_serve(args: argparse.Namespace) -> None:
    import uvicorn

    root = Path(args.root).expanduser().resolve()
    if not root.is_dir():
        sys.exit(f"The shared folder does not exist: {root}")
    ensure_config_dir()
    ensure_certificate()
    pairing = Pairing(args.pair_minutes * 60) if args.pair else None
    app = create_app(root, args.read_only, args.show_hidden, int(args.max_upload_gb * 1024**3), pairing)

    print(f"LapDrive {VERSION}")
    print(f"Shared folder : {root}{'  (read-only)' if args.read_only else ''}")
    print(f"Listening on  : https://{args.host}:{args.port}")
    print(f"Addresses     : {', '.join(local_addresses()) or 'unknown'}")
    print(f"Paired devices: {len(DeviceStore().list())}")
    if pairing:
        print_pairing(args.port, pairing, args.pair_minutes * 60)
    else:
        print("Pairing is off. Run with --pair to add a phone.\n", flush=True)

    uvicorn.run(
        app,
        host=args.host,
        port=args.port,
        ssl_certfile=str(CERT_FILE),
        ssl_keyfile=str(KEY_FILE),
        log_level="info",
        server_header=False,
        proxy_headers=False,
    )


def cmd_devices(_: argparse.Namespace) -> None:
    devs = DeviceStore().list()
    if not devs:
        print("No paired devices.")
        return
    fmt = lambda t: dt.datetime.fromtimestamp(t).strftime("%Y-%m-%d %H:%M")  # noqa: E731
    print(f"{'ID':10} {'NAME':28} {'PAIRED':17} LAST SEEN")
    for d in devs:
        print(f"{d['id']:10} {d['name'][:28]:28} {fmt(d['paired_at']):17} {fmt(d['last_seen'])}")


def cmd_revoke(args: argparse.Namespace) -> None:
    if DeviceStore().revoke(args.id):
        print(f"Revoked device {args.id}. Its token stops working now.")
    else:
        sys.exit(f"No device with id {args.id}")


def cmd_reset_cert(_: argparse.Namespace) -> None:
    for f in (CERT_FILE, KEY_FILE):
        f.unlink(missing_ok=True)
    ensure_config_dir()
    ensure_certificate()
    print("New certificate created. Every phone must pair again.")
    STATE_FILE.unlink(missing_ok=True)


def main() -> None:
    ap = argparse.ArgumentParser(prog="lapdrive", description="Access the files of this laptop from your phone.")
    sub = ap.add_subparsers(dest="cmd", required=True)

    s = sub.add_parser("serve", help="Start the file server")
    s.add_argument("--root", default=str(Path.home()), help="Folder to share (default: your home folder)")
    s.add_argument("--host", default="0.0.0.0", help="Address to listen on (default: 0.0.0.0)")
    s.add_argument("--port", type=int, default=8443)
    s.add_argument("--pair", action="store_true", help="Allow one new phone to pair")
    s.add_argument("--pair-minutes", type=int, default=5, help="How long the pairing code stays valid")
    s.add_argument("--read-only", action="store_true", help="Do not allow upload, rename or delete")
    s.add_argument("--show-hidden", action="store_true", help="Show dot files and dot folders")
    s.add_argument("--max-upload-gb", type=float, default=20, help="Largest upload in GB (default: 20)")
    s.set_defaults(func=cmd_serve)

    sub.add_parser("devices", help="List paired phones").set_defaults(func=cmd_devices)
    r = sub.add_parser("revoke", help="Remove a paired phone")
    r.add_argument("id")
    r.set_defaults(func=cmd_revoke)
    sub.add_parser("reset-cert", help="Make a new certificate and remove all paired phones").set_defaults(
        func=cmd_reset_cert
    )

    args = ap.parse_args()
    args.func(args)


if __name__ == "__main__":
    main()
