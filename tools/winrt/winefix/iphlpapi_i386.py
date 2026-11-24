# Backport of Wine commit 4781a712 ("iphlpapi: Avoid out-of-bounds read if no network
# interfaces are present") into Wine 10.10's 32-bit iphlpapi.dll: GetAdaptersAddresses returns
# ERROR_NO_DATA when NSI reports no interfaces, instead of walking a zero-length adapter array.
import sys, struct, hashlib
src, dst = sys.argv[1], sys.argv[2]
ORIGINAL = "0923068b93de990b0c811f2cfee628289035809c0fd00d5f725ccfe204a04320"
b = bytearray(open(src, 'rb').read())
assert hashlib.sha256(b).hexdigest() == ORIGINAL, 'not the Wine 10.10 build this patch is for'
BASE = 0x10000000
SITE = 0x10004bc0            # imull $0x5a4,-0x38(%ebp),%edx  (needed = count * size)
CAVE = 0x1000f0a0            # slack after .text's code, inside its raw data
ERR_EXIT = 0x10005242        # NsiFreeTable(...); adapters_addresses_free(aa = -0x68(%ebp)); return edi
off = lambda va: va - BASE   # .text: file offset == RVA
assert bytes(b[off(SITE):off(SITE)+7]) == bytes.fromhex('6955c8a4050000')
assert all(x == 0 for x in b[off(CAVE):off(CAVE)+0x40])
rel = lambda frm_next, to: struct.pack('<i', to - frm_next)
b[off(SITE):off(SITE)+7] = b'\xe9' + rel(SITE+5, CAVE) + b'\x90\x90'
cave = bytearray()
cave += bytes.fromhex('837dc800')          # cmpl $0x0,-0x38(%ebp)      count == 0 ?
cave += bytes.fromhex('7511')              # jne  cont
cave += bytes.fromhex('c7459800000000')    # movl $0x0,-0x68(%ebp)      aa = NULL
cave += bytes.fromhex('bfe8000000')        # movl $0xe8,%edi            ERROR_NO_DATA
cave += b'\xe9' + rel(CAVE+len(cave)+5, ERR_EXIT)
assert len(cave) == 23
cave += bytes.fromhex('6955c8a4050000')    # cont: imull $0x5a4,-0x38(%ebp),%edx
cave += b'\xe9' + rel(CAVE+len(cave)+5, SITE+7)
b[off(CAVE):off(CAVE)+len(cave)] = cave
# Grow .text's VirtualSize over the stub so it is mapped as code.
pe = struct.unpack_from('<I', b, 0x3c)[0]; opt = struct.unpack_from('<H', b, pe+20)[0]
sec = pe + 24 + opt
assert b[sec:sec+5] == b'.text'
vs, va, rs = struct.unpack_from('<III', b, sec+8)
need = CAVE - BASE - va + len(cave)
assert need <= rs
struct.pack_into('<I', b, sec+8, max(vs, (need + 0xf) & ~0xf))
open(dst, 'wb').write(b)
print('patched', dst, hashlib.sha256(b).hexdigest())
