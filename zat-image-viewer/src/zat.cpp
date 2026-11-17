// zat Image Viewer - lightweight native Windows image viewer.
// C++17, Win32 + WIC (decoding) + GDI+ (rendering). No external runtime needed.
//
// Layout of this file:
//   1. globals / helpers        5. toolbar (layout, painting, icons)
//   2. image list (folder)      6. annotation + saving
//   3. async decoding (WIC)     7. window procedure
//   4. animation (GIF/WebP)     8. WinMain

#define UNICODE
#define _UNICODE
#define NOMINMAX
#ifndef _WIN32_WINNT
#define _WIN32_WINNT 0x0601
#endif
#include <windows.h>
#include <windowsx.h>
#include <objidl.h>
#include <shellapi.h>
#include <shlwapi.h>
#include <commdlg.h>
#include <wincodec.h>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstdlib>
#include <cstring>
#include <string>
#include <vector>
using std::max;
using std::min;
#include <gdiplus.h>
using namespace Gdiplus;

#ifdef ZAT_NANOSVG
#define NANOSVG_IMPLEMENTATION
#include "third_party/nanosvg.h"
#define NANOSVGRAST_IMPLEMENTATION
#include "third_party/nanosvgrast.h"
#endif

// ---------------------------------------------------------------- constants
static const wchar_t* kClassName = L"zatImageViewerWindow";
static const wchar_t* kAppName = L"zat";
enum { WM_LOADED = WM_APP + 1 };
enum { T_ANIM = 1, T_IDLE = 2 };
static const LONGLONG kMaxPixels = 28LL * 1000 * 1000;  // decode limit (~112 MB)
static const int kThickLevels[] = {2, 3, 4, 6, 8, 12, 16, 24};
static const int kThickCount = (int)(sizeof(kThickLevels) / sizeof(kThickLevels[0]));
// {E094B0E2-67F2-45B3-B0EA-115337CA7CF3}
static const GUID kContainerWebp = {0xe094b0e2, 0x67f2, 0x45b3, {0xb0, 0xea, 0x11, 0x53, 0x37, 0xca, 0x7c, 0xf3}};

static const wchar_t* kExts[] = {L".jpg", L".jpeg", L".jpe", L".jfif", L".png", L".bmp", L".dib", L".gif",
                                 L".webp", L".tif", L".tiff", L".ico", L".svg", L".avif", L".heic", L".heif",
                                 L".jxr", L".wdp", L".hdp", L".jxl", L".dds"};

// ------------------------------------------------------------------ globals
struct Stroke {
    std::vector<PointF> pts;
    int color = 0;
    float width = 4;  // in image pixels
};

static HINSTANCE g_inst;
static HWND g_hwnd;
static IWICImagingFactory* g_wic;
static ULONG_PTR g_gdipToken;

static std::vector<std::wstring> g_files;
static int g_idx = -1;
static std::wstring g_curPath;

static BYTE* g_px = nullptr;  // premultiplied BGRA, g_w*g_h*4
static int g_w = 0, g_h = 0;
static Bitmap* g_bmp = nullptr;
static bool g_loading = false, g_failed = false, g_animated = false;
static unsigned g_gen = 0;

static int g_rot = 0;
static double g_scale = 1, g_panx = 0, g_pany = 0;
static bool g_fit = true;
static bool g_dark = true;
static bool g_annot = false;
static int g_color = 0, g_thick = 2;
static std::vector<Stroke> g_strokes;
static Stroke g_cur;
static bool g_drawing = false, g_panning = false, g_interacting = false;
static POINT g_last;
static int g_hover = -1, g_pressed = -1;
static bool g_firstImage = true, g_fullscreen = false, g_trackLeave = false;
static WINDOWPLACEMENT g_wp;
static int g_dpi = 96;

// back buffers: base layer (bg + image) and final frame
static HDC g_baseDC = nullptr, g_backDC = nullptr;
static HBITMAP g_baseBmp = nullptr, g_backBmp = nullptr, g_baseOld = nullptr, g_backOld = nullptr;
static int g_bufW = 0, g_bufH = 0;
static bool g_baseValid = false;

static int S(int v) { return MulDiv(v, g_dpi, 96); }
static float Sf(float v) { return v * g_dpi / 96.0f; }

static const COLORREF kAnnotRGB[3] = {RGB(229, 57, 53), RGB(46, 160, 67), RGB(250, 204, 21)};

// ------------------------------------------------------------------ helpers
static std::wstring Lower(std::wstring s) {
    for (auto& c : s) c = (wchar_t)towlower(c);
    return s;
}
static std::wstring ExtOf(const std::wstring& p) {
    const wchar_t* e = PathFindExtensionW(p.c_str());
    return Lower(e ? e : L"");
}
static bool IsSupported(const std::wstring& p) {
    std::wstring e = ExtOf(p);
    for (auto x : kExts)
        if (e == x) return true;
    return false;
}
static std::wstring FileName(const std::wstring& p) {
    const wchar_t* n = PathFindFileNameW(p.c_str());
    return n ? n : p;
}

static void SaveTheme() {
    HKEY k;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, L"Software\\zat", 0, nullptr, 0, KEY_SET_VALUE, nullptr, &k, nullptr) == ERROR_SUCCESS) {
        DWORD v = g_dark ? 1 : 0;
        RegSetValueExW(k, L"Theme", 0, REG_DWORD, (const BYTE*)&v, sizeof(v));
        RegCloseKey(k);
    }
}
static void LoadTheme() {
    HKEY k;
    g_dark = true;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, L"Software\\zat", 0, KEY_QUERY_VALUE, &k) == ERROR_SUCCESS) {
        DWORD v = 1, sz = sizeof(v), type = 0;
        if (RegQueryValueExW(k, L"Theme", nullptr, &type, (BYTE*)&v, &sz) == ERROR_SUCCESS && type == REG_DWORD) g_dark = v != 0;
        RegCloseKey(k);
    }
}

static void ApplyTitleBarTheme() {
    typedef HRESULT(WINAPI * PFN)(HWND, DWORD, LPCVOID, DWORD);
    HMODULE dwm = LoadLibraryW(L"dwmapi.dll");
    if (!dwm) return;
    PFN fn = (PFN)GetProcAddress(dwm, "DwmSetWindowAttribute");
    if (fn) {
        BOOL v = g_dark ? TRUE : FALSE;
        fn(g_hwnd, 20, &v, sizeof(v));
        fn(g_hwnd, 19, &v, sizeof(v));
    }
    FreeLibrary(dwm);
}

static int QueryDpi(HWND h) {
    typedef UINT(WINAPI * PFN)(HWND);
    HMODULE u = GetModuleHandleW(L"user32.dll");
    PFN fn = u ? (PFN)GetProcAddress(u, "GetDpiForWindow") : nullptr;
    if (fn && h) {
        UINT d = fn(h);
        if (d) return (int)d;
    }
    HDC dc = GetDC(nullptr);
    int d = GetDeviceCaps(dc, LOGPIXELSX);
    ReleaseDC(nullptr, dc);
    return d ? d : 96;
}

// ---------------------------------------------------------- 2. image list
static void BuildList(const std::vector<std::wstring>& args) {
    g_files.clear();
    g_idx = -1;
    std::vector<std::wstring> valid;
    for (auto& a : args) {
        wchar_t full[MAX_PATH * 2];
        DWORD n = GetFullPathNameW(a.c_str(), (DWORD)(sizeof(full) / sizeof(full[0])), full, nullptr);
        if (!n) continue;
        DWORD at = GetFileAttributesW(full);
        if (at == INVALID_FILE_ATTRIBUTES || (at & FILE_ATTRIBUTE_DIRECTORY)) continue;
        valid.push_back(full);
    }
    if (valid.empty()) return;
    if (valid.size() > 1) {
        for (auto& v : valid)
            if (IsSupported(v)) g_files.push_back(v);
        if (!g_files.empty()) g_idx = 0;
        return;
    }
    std::wstring dir = valid[0];
    wchar_t buf[MAX_PATH * 2];
    wcsncpy(buf, dir.c_str(), MAX_PATH * 2 - 1);
    buf[MAX_PATH * 2 - 1] = 0;
    PathRemoveFileSpecW(buf);
    dir = buf;
    WIN32_FIND_DATAW fd;
    HANDLE h = FindFirstFileW((dir + L"\\*").c_str(), &fd);
    if (h != INVALID_HANDLE_VALUE) {
        do {
            if (fd.dwFileAttributes & FILE_ATTRIBUTE_DIRECTORY) continue;
            std::wstring p = dir + L"\\" + fd.cFileName;
            if (IsSupported(p)) g_files.push_back(p);
        } while (FindNextFileW(h, &fd));
        FindClose(h);
    }
    std::sort(g_files.begin(), g_files.end(), [](const std::wstring& a, const std::wstring& b) {
        return StrCmpLogicalW(FileName(a).c_str(), FileName(b).c_str()) < 0;
    });
    for (size_t i = 0; i < g_files.size(); i++)
        if (lstrcmpiW(g_files[i].c_str(), valid[0].c_str()) == 0) g_idx = (int)i;
    if (g_idx < 0) {  // unknown extension: still open it
        g_files.push_back(valid[0]);
        g_idx = (int)g_files.size() - 1;
    }
}

// ------------------------------------------------ 3. async decoding (WIC)
struct LoadReq {
    unsigned gen;
    std::wstring path;
    HWND hwnd;
};
struct LoadResult {
    unsigned gen = 0;
    std::wstring path;
    BYTE* px = nullptr;
    int w = 0, h = 0;
    int orient = 1;
    bool animated = false;
    bool ok = false;
};

static bool QueryUInt(IWICMetadataQueryReader* r, LPCWSTR name, UINT& out) {
    if (!r) return false;
    PROPVARIANT pv;
    PropVariantInit(&pv);
    bool ok = false;
    if (SUCCEEDED(r->GetMetadataByName(name, &pv))) {
        switch (pv.vt) {
            case VT_UI1: out = pv.bVal; ok = true; break;
            case VT_UI2: out = pv.uiVal; ok = true; break;
            case VT_UI4: out = pv.ulVal; ok = true; break;
            case VT_I2: out = (UINT)pv.iVal; ok = true; break;
            case VT_I4: out = (UINT)pv.lVal; ok = true; break;
            default: break;
        }
    }
    PropVariantClear(&pv);
    return ok;
}

#ifdef ZAT_NANOSVG
static bool DecodeSvg(LoadResult& r) {
    HANDLE f = CreateFileW(r.path.c_str(), GENERIC_READ, FILE_SHARE_READ, nullptr, OPEN_EXISTING, 0, nullptr);
    if (f == INVALID_HANDLE_VALUE) return false;
    LARGE_INTEGER sz;
    if (!GetFileSizeEx(f, &sz) || sz.QuadPart <= 0 || sz.QuadPart > 64LL * 1024 * 1024) {
        CloseHandle(f);
        return false;
    }
    char* data = (char*)malloc((size_t)sz.QuadPart + 1);
    DWORD rd = 0;
    if (!data || !ReadFile(f, data, (DWORD)sz.QuadPart, &rd, nullptr)) {
        free(data);
        CloseHandle(f);
        return false;
    }
    CloseHandle(f);
    data[rd] = 0;
    NSVGimage* img = nsvgParse(data, "px", 96.0f);
    free(data);
    if (!img) return false;
    float iw = img->width, ih = img->height;
    if (iw < 1 || ih < 1) {
        iw = 512;
        ih = 512;
    }
    float target = 2048.0f;
    float sc = target / std::max(iw, ih);
    int W = std::max(1, (int)(iw * sc)), H = std::max(1, (int)(ih * sc));
    NSVGrasterizer* rast = nsvgCreateRasterizer();
    BYTE* buf = (BYTE*)malloc((size_t)W * H * 4);
    if (!rast || !buf) {
        free(buf);
        if (rast) nsvgDeleteRasterizer(rast);
        nsvgDelete(img);
        return false;
    }
    memset(buf, 0, (size_t)W * H * 4);
    nsvgRasterize(rast, img, 0, 0, sc, buf, W, H, W * 4);
    nsvgDeleteRasterizer(rast);
    nsvgDelete(img);
    for (size_t i = 0; i < (size_t)W * H; i++) {  // RGBA straight -> BGRA premultiplied
        BYTE* p = buf + i * 4;
        BYTE R = p[0], G = p[1], B = p[2], A = p[3];
        p[0] = (BYTE)((B * A + 127) / 255);
        p[1] = (BYTE)((G * A + 127) / 255);
        p[2] = (BYTE)((R * A + 127) / 255);
    }
    r.px = buf;
    r.w = W;
    r.h = H;
    return true;
}
#endif

template <class T>
static void SafeRelease(T*& p) {
    if (p) {
        p->Release();
        p = nullptr;
    }
}

static bool DecodeFile(LoadResult& r) {
    if (ExtOf(r.path) == L".svg") {
#ifdef ZAT_NANOSVG
        return DecodeSvg(r);
#else
        return false;
#endif
    }
    IWICImagingFactory* f = nullptr;
    if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&f)))) return false;
    IWICBitmapDecoder* dec = nullptr;
    IWICBitmapFrameDecode* fr = nullptr;
    IWICBitmapSource* src = nullptr;
    IWICBitmapScaler* scaler = nullptr;
    IWICFormatConverter* conv = nullptr;
    bool ok = false;
    do {
        if (FAILED(f->CreateDecoderFromFilename(r.path.c_str(), nullptr, GENERIC_READ, WICDecodeMetadataCacheOnDemand, &dec))) break;
        UINT count = 1;
        dec->GetFrameCount(&count);
        GUID cf = {};
        dec->GetContainerFormat(&cf);
        if (FAILED(dec->GetFrame(0, &fr))) break;
        UINT w = 0, h = 0;
        if (FAILED(fr->GetSize(&w, &h)) || !w || !h) break;
        bool animCandidate = (cf == GUID_ContainerFormatGif || cf == kContainerWebp) && count > 1;
        if (animCandidate) {
            IWICMetadataQueryReader* mr = nullptr;
            UINT lw = 0, lh = 0;
            if (SUCCEEDED(dec->GetMetadataQueryReader(&mr)) && mr) {
                QueryUInt(mr, L"/logscrdesc/Width", lw);
                QueryUInt(mr, L"/logscrdesc/Height", lh);
                mr->Release();
            }
            r.animated = true;
            r.w = (int)(lw ? lw : w);
            r.h = (int)(lh ? lh : h);
            ok = true;
            break;
        }
        // orientation (EXIF)
        IWICMetadataQueryReader* fmr = nullptr;
        if (SUCCEEDED(fr->GetMetadataQueryReader(&fmr)) && fmr) {
            UINT o = 1;
            if (QueryUInt(fmr, L"/app1/ifd/{ushort=274}", o) || QueryUInt(fmr, L"/ifd/{ushort=274}", o)) r.orient = (int)o;
            fmr->Release();
        }
        UINT tw = w, th = h;
        if ((LONGLONG)w * h > kMaxPixels) {
            double k = std::sqrt((double)kMaxPixels / ((double)w * h));
            tw = std::max(1u, (UINT)(w * k));
            th = std::max(1u, (UINT)(h * k));
        }
        src = fr;
        src->AddRef();
        if (tw != w || th != h) {
            if (FAILED(f->CreateBitmapScaler(&scaler))) break;
            if (FAILED(scaler->Initialize(fr, tw, th, WICBitmapInterpolationModeFant))) break;
            src->Release();
            src = scaler;
            src->AddRef();
        }
        if (FAILED(f->CreateFormatConverter(&conv))) break;
        if (FAILED(conv->Initialize(src, GUID_WICPixelFormat32bppPBGRA, WICBitmapDitherTypeNone, nullptr, 0.0, WICBitmapPaletteTypeCustom))) break;
        size_t bytes = (size_t)tw * th * 4;
        BYTE* buf = (BYTE*)malloc(bytes);
        if (!buf) break;
        if (FAILED(conv->CopyPixels(nullptr, tw * 4, (UINT)bytes, buf))) {
            free(buf);
            break;
        }
        r.px = buf;
        r.w = (int)tw;
        r.h = (int)th;
        ok = true;
    } while (false);
    SafeRelease(conv);
    SafeRelease(scaler);
    SafeRelease(src);
    SafeRelease(fr);
    SafeRelease(dec);
    f->Release();
    return ok;
}

static DWORD WINAPI LoadThread(LPVOID p) {
    LoadReq* rq = (LoadReq*)p;
    CoInitializeEx(nullptr, COINIT_MULTITHREADED);
    LoadResult* r = new LoadResult();
    r->gen = rq->gen;
    r->path = rq->path;
    r->ok = DecodeFile(*r);
    CoUninitialize();
    if (!PostMessageW(rq->hwnd, WM_LOADED, 0, (LPARAM)r)) {
        free(r->px);
        delete r;
    }
    delete rq;
    return 0;
}

// ------------------------------------------------------ 4. animation (main)
struct Anim {
    IWICBitmapDecoder* dec = nullptr;
    UINT count = 0, cur = 0;
    BYTE* prev = nullptr;
    int dispPrev = 0;
    RECT rcPrev = {0, 0, 0, 0};
} g_anim;

static void StopAnim() {
    KillTimer(g_hwnd, T_ANIM);
    SafeRelease(g_anim.dec);
    free(g_anim.prev);
    g_anim = Anim();
}

static void InvalidateBase() {
    g_baseValid = false;
    if (g_hwnd) InvalidateRect(g_hwnd, nullptr, FALSE);
}

static void AnimStep() {
    if (!g_anim.dec || !g_px) return;
    UINT i = g_anim.cur;
    size_t canvasBytes = (size_t)g_w * g_h * 4;
    if (i == 0) {
        memset(g_px, 0, canvasBytes);
        g_anim.dispPrev = 0;
    } else if (g_anim.dispPrev == 2) {
        RECT rc = g_anim.rcPrev;
        rc.left = std::max<LONG>(0, rc.left);
        rc.top = std::max<LONG>(0, rc.top);
        rc.right = std::min<LONG>(g_w, rc.right);
        rc.bottom = std::min<LONG>(g_h, rc.bottom);
        for (LONG y = rc.top; y < rc.bottom; y++) memset(g_px + ((size_t)y * g_w + rc.left) * 4, 0, (size_t)(rc.right - rc.left) * 4);
    } else if (g_anim.dispPrev == 3 && g_anim.prev) {
        memcpy(g_px, g_anim.prev, canvasBytes);
    }
    UINT delayMs = 100;
    IWICBitmapFrameDecode* fr = nullptr;
    IWICFormatConverter* conv = nullptr;
    if (SUCCEEDED(g_anim.dec->GetFrame(i, &fr))) {
        UINT fw = 0, fh = 0;
        fr->GetSize(&fw, &fh);
        UINT left = 0, top = 0, disposal = 0, delay = 0;
        IWICMetadataQueryReader* mr = nullptr;
        bool haveMeta = false;
        if (SUCCEEDED(fr->GetMetadataQueryReader(&mr)) && mr) {
            haveMeta = QueryUInt(mr, L"/imgdesc/Left", left);
            QueryUInt(mr, L"/imgdesc/Top", top);
            QueryUInt(mr, L"/grctlext/Disposal", disposal);
            if (QueryUInt(mr, L"/grctlext/Delay", delay) && delay > 1) delayMs = delay * 10;
            mr->Release();
        }
        if (!haveMeta) {  // frames are already full images (e.g. WebP)
            memset(g_px, 0, canvasBytes);
            left = top = 0;
            disposal = 0;
        }
        if (disposal == 3) {
            if (!g_anim.prev) g_anim.prev = (BYTE*)malloc(canvasBytes);
            if (g_anim.prev) memcpy(g_anim.prev, g_px, canvasBytes);
        }
        if (fw && fh && fw < 20000 && fh < 20000 && SUCCEEDED(g_wic->CreateFormatConverter(&conv)) &&
            SUCCEEDED(conv->Initialize(fr, GUID_WICPixelFormat32bppPBGRA, WICBitmapDitherTypeNone, nullptr, 0.0, WICBitmapPaletteTypeCustom))) {
            size_t bytes = (size_t)fw * fh * 4;
            BYTE* tmp = (BYTE*)malloc(bytes);
            if (tmp && SUCCEEDED(conv->CopyPixels(nullptr, fw * 4, (UINT)bytes, tmp))) {
                for (UINT y = 0; y < fh; y++) {
                    int dy = (int)(top + y);
                    if (dy < 0 || dy >= g_h) continue;
                    for (UINT x = 0; x < fw; x++) {
                        int dx = (int)(left + x);
                        if (dx < 0 || dx >= g_w) continue;
                        const BYTE* s = tmp + ((size_t)y * fw + x) * 4;
                        BYTE* d = g_px + ((size_t)dy * g_w + dx) * 4;
                        unsigned sa = s[3];
                        if (sa == 255) {
                            memcpy(d, s, 4);
                        } else if (sa) {
                            unsigned inv = 255 - sa;
                            d[0] = (BYTE)(s[0] + (d[0] * inv + 127) / 255);
                            d[1] = (BYTE)(s[1] + (d[1] * inv + 127) / 255);
                            d[2] = (BYTE)(s[2] + (d[2] * inv + 127) / 255);
                            d[3] = (BYTE)(s[3] + (d[3] * inv + 127) / 255);
                        }
                    }
                }
            }
            free(tmp);
        }
        g_anim.dispPrev = (int)disposal;
        g_anim.rcPrev.left = (LONG)left;
        g_anim.rcPrev.top = (LONG)top;
        g_anim.rcPrev.right = (LONG)(left + fw);
        g_anim.rcPrev.bottom = (LONG)(top + fh);
    }
    SafeRelease(conv);
    SafeRelease(fr);
    g_anim.cur = (i + 1) % std::max(1u, g_anim.count);
    SetTimer(g_hwnd, T_ANIM, delayMs, nullptr);
    InvalidateBase();
}

static bool StartAnim(const std::wstring& path) {
    if (FAILED(g_wic->CreateDecoderFromFilename(path.c_str(), nullptr, GENERIC_READ, WICDecodeMetadataCacheOnDemand, &g_anim.dec))) return false;
    g_anim.dec->GetFrameCount(&g_anim.count);
    g_anim.cur = 0;
    return g_anim.count > 0;
}

// ------------------------------------------------------- view / transform
static void ClientSize(double& cw, double& ch) {
    RECT rc;
    GetClientRect(g_hwnd, &rc);
    cw = std::max<LONG>(1, rc.right);
    ch = std::max<LONG>(1, rc.bottom);
}
static void EffDims(double& ew, double& eh) {
    if (g_rot & 1) {
        ew = g_h;
        eh = g_w;
    } else {
        ew = g_w;
        eh = g_h;
    }
}
static double FitScale() {
    if (!g_w || !g_h) return 1;
    double cw, ch, ew, eh;
    ClientSize(cw, ch);
    EffDims(ew, eh);
    double s = std::min(cw / ew, ch / eh);
    return s > 1 ? 1 : s;
}
static void ClampPan() {
    if (!g_w) return;
    double cw, ch, ew, eh;
    ClientSize(cw, ch);
    EffDims(ew, eh);
    double dw = ew * g_scale, dh = eh * g_scale;
    double mx = dw > cw ? (dw - cw) / 2 : 0, my = dh > ch ? (dh - ch) / 2 : 0;
    g_panx = std::max(-mx, std::min(mx, g_panx));
    g_pany = std::max(-my, std::min(my, g_pany));
}
static void ApplyFit() {
    g_scale = FitScale();
    g_panx = g_pany = 0;
    g_fit = true;
}
static void UpdateTitle() {
    std::wstring t;
    if (!g_curPath.empty()) {
        t = FileName(g_curPath);
        if (g_w) t += L"  -  " + std::to_wstring(g_w) + L"x" + std::to_wstring(g_h);
        if (g_w) t += L"  -  " + std::to_wstring((int)(g_scale * 100 + 0.5)) + L"%";
        if (g_files.size() > 1) t += L"  -  " + std::to_wstring(g_idx + 1) + L"/" + std::to_wstring(g_files.size());
        t += L"  -  ";
    }
    t += kAppName;
    SetWindowTextW(g_hwnd, t.c_str());
}
static void RotM(double& c, double& s) {
    static const double C[4] = {1, 0, -1, 0}, Sn[4] = {0, 1, 0, -1};
    c = C[g_rot & 3];
    s = Sn[g_rot & 3];
}
// screen = C + pan + s * R * (p - c0)
static PointF ScreenToImage(double sx, double sy) {
    double cw, ch;
    ClientSize(cw, ch);
    double dx = sx - cw / 2 - g_panx, dy = sy - ch / 2 - g_pany;
    double c, s;
    RotM(c, s);
    double rx = dx * c + dy * s, ry = -dx * s + dy * c;  // R^-1
    return PointF((REAL)(g_w / 2.0 + rx / g_scale), (REAL)(g_h / 2.0 + ry / g_scale));
}
static void ZoomAt(double ns, double sx, double sy) {
    if (!g_w) return;
    ns = std::max(0.01, std::min(64.0, ns));
    PointF p = ScreenToImage(sx, sy);
    double cw, ch, c, s;
    ClientSize(cw, ch);
    RotM(c, s);
    double ox = p.X - g_w / 2.0, oy = p.Y - g_h / 2.0;
    double rx = ox * c - oy * s, ry = ox * s + oy * c;
    g_scale = ns;
    g_panx = sx - cw / 2 - ns * rx;
    g_pany = sy - ch / 2 - ns * ry;
    g_fit = false;
    ClampPan();
    g_interacting = true;
    SetTimer(g_hwnd, T_IDLE, 140, nullptr);
    UpdateTitle();
    InvalidateBase();
}
static void ZoomCenter(double factor) {
    double cw, ch;
    ClientSize(cw, ch);
    ZoomAt(g_scale * factor, cw / 2, ch / 2);
}
static void Rotate90() {
    if (!g_w) return;
    g_rot = (g_rot + 1) & 3;
    if (g_fit) ApplyFit();
    else ClampPan();
    UpdateTitle();
    InvalidateBase();
}

// ------------------------------------------------ load / free / navigation
static void FreeImage() {
    StopAnim();
    delete g_bmp;
    g_bmp = nullptr;
    free(g_px);
    g_px = nullptr;
    g_w = g_h = 0;
    g_animated = false;
}
static bool ConfirmDiscard() {
    if (g_strokes.empty()) return true;
    return MessageBoxW(g_hwnd, L"Discard your annotations on this image?", L"zat", MB_YESNO | MB_ICONQUESTION) == IDYES;
}
static void StartLoad(const std::wstring& path) {
    g_gen++;
    g_loading = true;
    LoadReq* rq = new LoadReq{g_gen, path, g_hwnd};
    HANDLE h = CreateThread(nullptr, 0, LoadThread, rq, 0, nullptr);
    if (h) CloseHandle(h);
    else {
        delete rq;
        g_loading = false;
    }
}
static void GoTo(int i) {
    if (g_files.empty() || i < 0 || i >= (int)g_files.size()) return;
    if (!ConfirmDiscard()) return;
    g_idx = i;
    StartLoad(g_files[i]);
}
static void Step(int d) {
    int n = (int)g_files.size();
    if (n < 2) return;
    GoTo(((g_idx + d) % n + n) % n);
}

static void FitWindowToImage() {
    if (!g_firstImage || IsZoomed(g_hwnd) || g_fullscreen) return;
    g_firstImage = false;
    RECT wa;
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
    RECT cr, wr;
    GetClientRect(g_hwnd, &cr);
    GetWindowRect(g_hwnd, &wr);
    int chromeW = (wr.right - wr.left) - cr.right, chromeH = (wr.bottom - wr.top) - cr.bottom;
    int maxW = (int)((wa.right - wa.left) * 0.9), maxH = (int)((wa.bottom - wa.top) * 0.9);
    double ew, eh;
    EffDims(ew, eh);
    int w = std::min(maxW, std::max(S(520), (int)ew + chromeW)), h = std::min(maxH, std::max(S(400), (int)eh + chromeH));
    int x = wa.left + ((wa.right - wa.left) - w) / 2, y = wa.top + ((wa.bottom - wa.top) - h) / 2;
    SetWindowPos(g_hwnd, nullptr, x, y, w, h, SWP_NOZORDER | SWP_NOACTIVATE);
}

static void OnLoaded(LoadResult* r) {
    if (r->gen != g_gen) {
        free(r->px);
        delete r;
        return;
    }
    g_loading = false;
    FreeImage();
    g_strokes.clear();
    g_cur = Stroke();
    g_drawing = false;
    g_curPath = r->path;
    g_rot = 0;
    if (!r->ok) {
        g_failed = true;
        free(r->px);
        delete r;
        g_annot = false;
        UpdateTitle();
        InvalidateBase();
        return;
    }
    g_failed = false;
    g_w = r->w;
    g_h = r->h;
    if (r->animated) {
        g_animated = true;
        g_annot = false;
        g_px = (BYTE*)calloc((size_t)g_w * g_h, 4);
        if (!g_px || !StartAnim(r->path)) {
            FreeImage();
            g_failed = true;
            delete r;
            UpdateTitle();
            InvalidateBase();
            return;
        }
        g_bmp = new Bitmap(g_w, g_h, g_w * 4, PixelFormat32bppPARGB, g_px);
        AnimStep();
    } else {
        g_px = r->px;
        r->px = nullptr;
        g_bmp = new Bitmap(g_w, g_h, g_w * 4, PixelFormat32bppPARGB, g_px);
        if (r->orient == 6) g_rot = 1;
        else if (r->orient == 3) g_rot = 2;
        else if (r->orient == 8) g_rot = 3;
    }
    delete r;
    FitWindowToImage();
    ApplyFit();
    UpdateTitle();
    InvalidateBase();
}

// ---------------------------------------------------------- 5. toolbar
enum {
    B_THEME = 1, B_PEN, B_SAVE, B_RED, B_GREEN, B_YELLOW, B_TMINUS, B_TDOT, B_TPLUS, B_UNDO, B_CLEAR,
    B_PREV, B_NEXT, B_ZIN, B_ZOUT, B_ROT, B_FIT
};
struct Btn {
    int id;
    RECT r;
    bool enabled, active;
};
struct Bars {
    RECT tl, tr, bot;
    bool hasTR;
};

static void Layout(std::vector<Btn>& v, Bars& bars) {
    v.clear();
    RECT rc;
    GetClientRect(g_hwnd, &rc);
    int bs = S(38), gap = S(3), pad = S(5), mar = S(10), sep = S(8), dotW = S(26);
    // top-left: theme
    bars.tl = {mar, mar, mar + bs + 2 * pad, mar + bs + 2 * pad};
    v.push_back({B_THEME, {bars.tl.left + pad, bars.tl.top + pad, bars.tl.left + pad + bs, bars.tl.top + pad + bs}, true, false});
    // top-right: [colors][thickness][undo clear save] pen
    struct It { int id; int w; int before; };
    std::vector<It> items;
    if (g_annot) {
        items = {{B_RED, bs, 0}, {B_GREEN, bs, 0}, {B_YELLOW, bs, 0}, {B_TMINUS, bs, sep}, {B_TDOT, dotW, 0}, {B_TPLUS, bs, 0},
                 {B_UNDO, bs, sep}, {B_CLEAR, bs, 0}, {B_SAVE, bs, sep}, {B_PEN, bs, 0}};
    } else {
        items = {{B_PEN, bs, 0}};
    }
    int total = 2 * pad;
    for (size_t i = 0; i < items.size(); i++) total += items[i].w + (i ? gap + items[i].before : 0);
    int right = rc.right - mar;
    bars.tr = {right - total, mar, right, mar + bs + 2 * pad};
    int x = bars.tr.left + pad;
    for (size_t i = 0; i < items.size(); i++) {
        if (i) x += gap + items[i].before;
        bool en = true, act = false;
        switch (items[i].id) {
            case B_PEN: en = !g_animated && g_w > 0; act = g_annot; break;
            case B_RED: act = g_color == 0; break;
            case B_GREEN: act = g_color == 1; break;
            case B_YELLOW: act = g_color == 2; break;
            case B_TMINUS: en = g_thick > 0; break;
            case B_TPLUS: en = g_thick < kThickCount - 1; break;
            case B_UNDO: case B_CLEAR: en = !g_strokes.empty(); break;
            case B_SAVE: en = !g_strokes.empty(); break;
            default: break;
        }
        v.push_back({items[i].id, {x, bars.tr.top + pad, x + items[i].w, bars.tr.top + pad + bs}, en, act});
        x += items[i].w;
    }
    bars.hasTR = true;
    // bottom center
    const int ids[] = {B_PREV, B_NEXT, B_ZOUT, B_ZIN, B_ROT, B_FIT};
    int n = 6;
    int bw = n * bs + (n - 1) * gap + 2 * pad;
    int bx = (rc.right - bw) / 2;
    int by = rc.bottom - mar - bs - 2 * pad;
    bars.bot = {bx, by, bx + bw, by + bs + 2 * pad};
    for (int i = 0; i < n; i++) {
        int px = bx + pad + i * (bs + gap);
        bool en = g_w > 0;
        if ((ids[i] == B_PREV || ids[i] == B_NEXT)) en = g_files.size() > 1;
        v.push_back({ids[i], {px, by + pad, px + bs, by + pad + bs}, en, false});
    }
}
static int HitTest(const std::vector<Btn>& v, int x, int y) {
    for (auto& b : v)
        if (b.id != B_TDOT && x >= b.r.left && x < b.r.right && y >= b.r.top && y < b.r.bottom) return b.id;
    return -1;
}
static bool InBars(const Bars& b, int x, int y) {
    POINT p = {x, y};
    return PtInRect(&b.tl, p) || (b.hasTR && PtInRect(&b.tr, p)) || PtInRect(&b.bot, p);
}

static void FillRound(Graphics& g, const Brush& br, RectF r, float rad) {
    GraphicsPath p;
    float d = rad * 2;
    p.AddArc(r.X, r.Y, d, d, 180, 90);
    p.AddArc(r.X + r.Width - d, r.Y, d, d, 270, 90);
    p.AddArc(r.X + r.Width - d, r.Y + r.Height - d, d, d, 0, 90);
    p.AddArc(r.X, r.Y + r.Height - d, d, d, 90, 90);
    p.CloseFigure();
    g.FillPath(&br, &p);
}
static void StrokeRound(Graphics& g, const Pen& pen, RectF r, float rad) {
    GraphicsPath p;
    float d = rad * 2;
    p.AddArc(r.X, r.Y, d, d, 180, 90);
    p.AddArc(r.X + r.Width - d, r.Y, d, d, 270, 90);
    p.AddArc(r.X + r.Width - d, r.Y + r.Height - d, d, d, 0, 90);
    p.AddArc(r.X, r.Y + r.Height - d, d, d, 90, 90);
    p.CloseFigure();
    g.DrawPath(&pen, &p);
}

static void DrawIconFor(Graphics& g, const Btn& b, Color fg) {
    float cx = (b.r.left + b.r.right) / 2.0f, cy = (b.r.top + b.r.bottom) / 2.0f;
    float h = Sf(9.5f);
    if (!b.enabled) fg = Color(90, fg.GetR(), fg.GetG(), fg.GetB());
    Pen pen(fg, Sf(1.8f));
    pen.SetStartCap(LineCapRound);
    pen.SetEndCap(LineCapRound);
    pen.SetLineJoin(LineJoinRound);
    SolidBrush fb(fg);
    switch (b.id) {
        case B_THEME:
            if (g_dark) {  // sun
                g.DrawEllipse(&pen, cx - 0.5f * h, cy - 0.5f * h, h, h);
                for (int i = 0; i < 8; i++) {
                    double a = i * 3.14159265 / 4;
                    g.DrawLine(&pen, (REAL)(cx + std::cos(a) * 0.85 * h), (REAL)(cy + std::sin(a) * 0.85 * h),
                               (REAL)(cx + std::cos(a) * 1.15 * h), (REAL)(cy + std::sin(a) * 1.15 * h));
                }
            } else {  // moon
                GraphicsPath a, c;
                a.AddEllipse(cx - 0.95f * h, cy - 0.95f * h, 1.9f * h, 1.9f * h);
                c.AddEllipse(cx - 0.35f * h, cy - 1.3f * h, 1.7f * h, 1.7f * h);
                Region rg(&a);
                rg.Exclude(&c);
                g.FillRegion(&fb, &rg);
            }
            break;
        case B_PEN:
            g.DrawLine(&pen, cx - h, cy + h, cx - 0.2f * h, cy + 0.2f * h);
            {
                Pen thick(fg, Sf(4.2f));
                thick.SetStartCap(LineCapFlat);
                thick.SetEndCap(LineCapRound);
                g.DrawLine(&thick, cx - 0.2f * h, cy + 0.2f * h, cx + 0.8f * h, cy - 0.8f * h);
            }
            break;
        case B_SAVE:
            g.DrawRectangle(&pen, cx - h, cy - h, 2 * h, 2 * h);
            g.DrawRectangle(&pen, cx - 0.5f * h, cy - h, h, 0.6f * h);
            g.DrawRectangle(&pen, cx - 0.55f * h, cy + 0.15f * h, 1.1f * h, 0.85f * h);
            break;
        case B_RED: case B_GREEN: case B_YELLOW: {
            COLORREF c = kAnnotRGB[b.id - B_RED];
            SolidBrush cb(Color(255, GetRValue(c), GetGValue(c), GetBValue(c)));
            g.FillEllipse(&cb, cx - 0.7f * h, cy - 0.7f * h, 1.4f * h, 1.4f * h);
            if (b.active) {
                Pen ring(fg, Sf(1.6f));
                g.DrawEllipse(&ring, cx - 1.05f * h, cy - 1.05f * h, 2.1f * h, 2.1f * h);
            }
            break;
        }
        case B_TMINUS: g.DrawLine(&pen, cx - 0.7f * h, cy, cx + 0.7f * h, cy); break;
        case B_TPLUS:
            g.DrawLine(&pen, cx - 0.7f * h, cy, cx + 0.7f * h, cy);
            g.DrawLine(&pen, cx, cy - 0.7f * h, cx, cy + 0.7f * h);
            break;
        case B_TDOT: {
            float d = std::min(Sf((float)kThickLevels[g_thick]), Sf(22.0f));
            COLORREF c = kAnnotRGB[g_color];
            SolidBrush cb(Color(255, GetRValue(c), GetGValue(c), GetBValue(c)));
            g.FillEllipse(&cb, cx - d / 2, cy - d / 2, d, d);
            break;
        }
        case B_UNDO:
            g.DrawLine(&pen, cx + h, cy, cx - 0.8f * h, cy);
            g.DrawLine(&pen, cx - 0.8f * h, cy, cx - 0.1f * h, cy - 0.7f * h);
            g.DrawLine(&pen, cx - 0.8f * h, cy, cx - 0.1f * h, cy + 0.7f * h);
            break;
        case B_CLEAR:
            g.DrawLine(&pen, cx - 0.75f * h, cy - 0.75f * h, cx + 0.75f * h, cy + 0.75f * h);
            g.DrawLine(&pen, cx - 0.75f * h, cy + 0.75f * h, cx + 0.75f * h, cy - 0.75f * h);
            break;
        case B_PREV: {
            PointF p[3] = {PointF(cx + 0.4f * h, cy - h), PointF(cx - 0.5f * h, cy), PointF(cx + 0.4f * h, cy + h)};
            g.DrawLines(&pen, p, 3);
            break;
        }
        case B_NEXT: {
            PointF p[3] = {PointF(cx - 0.4f * h, cy - h), PointF(cx + 0.5f * h, cy), PointF(cx - 0.4f * h, cy + h)};
            g.DrawLines(&pen, p, 3);
            break;
        }
        case B_ZIN: case B_ZOUT:
            g.DrawEllipse(&pen, cx - 0.95f * h, cy - 0.95f * h, 1.5f * h, 1.5f * h);
            g.DrawLine(&pen, cx + 0.2f * h, cy + 0.2f * h, cx + h, cy + h);
            g.DrawLine(&pen, cx - 0.6f * h, cy - 0.2f * h, cx - 0.0f * h, cy - 0.2f * h);
            if (b.id == B_ZIN) g.DrawLine(&pen, cx - 0.3f * h, cy - 0.5f * h, cx - 0.3f * h, cy + 0.1f * h);
            break;
        case B_ROT: {
            g.DrawArc(&pen, cx - 0.85f * h, cy - 0.85f * h, 1.7f * h, 1.7f * h, -50.0f, 280.0f);
            float ex = cx + 0.85f * h * (float)std::cos(230 * 3.14159265 / 180), ey = cy + 0.85f * h * (float)std::sin(230 * 3.14159265 / 180);
            PointF tri[3] = {PointF(ex + 0.55f * h, ey - 0.35f * h), PointF(ex - 0.45f * h, ey - 0.45f * h), PointF(ex + 0.2f * h, ey + 0.55f * h)};
            g.FillPolygon(&fb, tri, 3);
            break;
        }
        case B_FIT: {
            float a = 0.5f * h;
            PointF q[4][3] = {
                {PointF(cx - h, cy - h + a), PointF(cx - h, cy - h), PointF(cx - h + a, cy - h)},
                {PointF(cx + h - a, cy - h), PointF(cx + h, cy - h), PointF(cx + h, cy - h + a)},
                {PointF(cx + h, cy + h - a), PointF(cx + h, cy + h), PointF(cx + h - a, cy + h)},
                {PointF(cx - h + a, cy + h), PointF(cx - h, cy + h), PointF(cx - h, cy + h - a)}};
            for (auto& l : q) g.DrawLines(&pen, l, 3);
            break;
        }
        default: break;
    }
}

static void PaintBar(Graphics& g, RECT r) {
    RectF rf((REAL)r.left, (REAL)r.top, (REAL)(r.right - r.left), (REAL)(r.bottom - r.top));
    Color fill = g_dark ? Color(235, 36, 38, 44) : Color(240, 250, 250, 252);
    Color line = g_dark ? Color(255, 62, 65, 74) : Color(255, 212, 214, 220);
    SolidBrush br(fill);
    FillRound(g, br, rf, Sf(8));
    Pen pn(line, 1.0f);
    StrokeRound(g, pn, rf, Sf(8));
}

// ------------------------------------------------- 6. annotation + saving
static void DrawStrokesTo(Graphics& g, const std::vector<Stroke>& list, const Stroke* extra) {
    g.SetSmoothingMode(SmoothingModeAntiAlias);
    size_t n = list.size() + (extra && !extra->pts.empty() ? 1 : 0);
    for (size_t i = 0; i < n; i++) {
        const Stroke& s = i < list.size() ? list[i] : *extra;
        if (s.pts.empty()) continue;
        COLORREF c = kAnnotRGB[s.color];
        Color col(255, GetRValue(c), GetGValue(c), GetBValue(c));
        if (s.pts.size() == 1) {
            SolidBrush b(col);
            g.FillEllipse(&b, s.pts[0].X - s.width / 2, s.pts[0].Y - s.width / 2, s.width, s.width);
            continue;
        }
        Pen pen(col, s.width);
        pen.SetStartCap(LineCapRound);
        pen.SetEndCap(LineCapRound);
        pen.SetLineJoin(LineJoinRound);
        if (s.pts.size() == 2) g.DrawLine(&pen, s.pts[0], s.pts[1]);
        else g.DrawCurve(&pen, s.pts.data(), (INT)s.pts.size(), 0.4f);
    }
}

static bool GetEncoder(const wchar_t* mime, CLSID* clsid) {
    UINT n = 0, sz = 0;
    GetImageEncodersSize(&n, &sz);
    if (!sz) return false;
    ImageCodecInfo* info = (ImageCodecInfo*)malloc(sz);
    if (!info) return false;
    GetImageEncoders(n, sz, info);
    bool found = false;
    for (UINT i = 0; i < n; i++)
        if (wcscmp(info[i].MimeType, mime) == 0) {
            *clsid = info[i].Clsid;
            found = true;
            break;
        }
    free(info);
    return found;
}
static const wchar_t* MimeForExt(const std::wstring& ext) {
    if (ext == L".png") return L"image/png";
    if (ext == L".jpg" || ext == L".jpeg" || ext == L".jpe" || ext == L".jfif") return L"image/jpeg";
    if (ext == L".bmp" || ext == L".dib") return L"image/bmp";
    if (ext == L".tif" || ext == L".tiff") return L"image/tiff";
    if (ext == L".gif") return L"image/gif";
    return nullptr;
}

static bool WriteAnnotated(const std::wstring& path, const wchar_t* mime) {
    if (!g_bmp || !g_px) return false;
    CLSID clsid;
    if (!GetEncoder(mime, &clsid)) return false;
    bool jpeg = wcscmp(mime, L"image/jpeg") == 0 || wcscmp(mime, L"image/bmp") == 0;
    Bitmap out(g_w, g_h, PixelFormat32bppPARGB);
    {
        Graphics g(&out);
        if (jpeg) g.Clear(Color(255, 255, 255, 255));
        g.SetInterpolationMode(InterpolationModeNearestNeighbor);
        g.SetPixelOffsetMode(PixelOffsetModeHalf);
        g.DrawImage(g_bmp, 0, 0, g_w, g_h);
        DrawStrokesTo(g, g_strokes, nullptr);
    }
    if (g_rot == 1) out.RotateFlip(Rotate90FlipNone);
    else if (g_rot == 2) out.RotateFlip(Rotate180FlipNone);
    else if (g_rot == 3) out.RotateFlip(Rotate270FlipNone);
    Status st;
    if (wcscmp(mime, L"image/jpeg") == 0) {
        EncoderParameters ep;
        ep.Count = 1;
        ep.Parameter[0].Guid = EncoderQuality;
        ep.Parameter[0].Type = EncoderParameterValueTypeLong;
        ep.Parameter[0].NumberOfValues = 1;
        ULONG q = 95;
        ep.Parameter[0].Value = &q;
        st = out.Save(path.c_str(), &clsid, &ep);
    } else {
        st = out.Save(path.c_str(), &clsid, nullptr);
    }
    return st == Ok;
}

static void SaveAsNew() {
    if (g_strokes.empty() || !g_bmp) return;
    wchar_t file[MAX_PATH * 2] = {0};
    std::wstring name = FileName(g_curPath);
    size_t dot = name.find_last_of(L'.');
    std::wstring stem = dot == std::wstring::npos ? name : name.substr(0, dot);
    std::wstring def = stem + L"_annotated";
    wcsncpy(file, def.c_str(), MAX_PATH - 1);
    wchar_t dir[MAX_PATH * 2];
    wcsncpy(dir, g_curPath.c_str(), MAX_PATH * 2 - 1);
    dir[MAX_PATH * 2 - 1] = 0;
    PathRemoveFileSpecW(dir);
    OPENFILENAMEW ofn = {};
    ofn.lStructSize = sizeof(ofn);
    ofn.hwndOwner = g_hwnd;
    ofn.lpstrFilter = L"PNG image (*.png)\0*.png\0JPEG image (*.jpg)\0*.jpg\0BMP image (*.bmp)\0*.bmp\0TIFF image (*.tif)\0*.tif\0\0";
    ofn.lpstrFile = file;
    ofn.nMaxFile = MAX_PATH * 2;
    ofn.lpstrInitialDir = dir;
    ofn.lpstrDefExt = L"png";
    ofn.lpstrTitle = L"Save annotated image as";
    ofn.Flags = OFN_OVERWRITEPROMPT | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;
    if (!GetSaveFileNameW(&ofn)) return;
    std::wstring out = file;
    std::wstring ext = ExtOf(out);
    const wchar_t* mime = MimeForExt(ext);
    if (!mime) {
        static const wchar_t* byFilter[] = {L".png", L".jpg", L".bmp", L".tif"};
        int fi = (int)ofn.nFilterIndex - 1;
        if (fi < 0 || fi > 3) fi = 0;
        out += byFilter[fi];
        mime = MimeForExt(byFilter[fi]);
    }
    if (!WriteAnnotated(out, mime)) MessageBoxW(g_hwnd, L"Could not save the image.", L"zat", MB_OK | MB_ICONERROR);
}

static void OverwriteOriginal() {
    if (g_strokes.empty() || !g_bmp) return;
    const wchar_t* mime = MimeForExt(ExtOf(g_curPath));
    if (!mime) {
        MessageBoxW(g_hwnd, L"This format cannot be overwritten. Please use \"Save as new file\".", L"zat", MB_OK | MB_ICONINFORMATION);
        SaveAsNew();
        return;
    }
    if (MessageBoxW(g_hwnd, L"Overwrite the original file with the annotated image?\nThis cannot be undone.", L"zat",
                    MB_YESNO | MB_ICONWARNING | MB_DEFBUTTON2) != IDYES)
        return;
    std::wstring tmp = g_curPath + L".zat.tmp";
    if (!WriteAnnotated(tmp, mime) || !MoveFileExW(tmp.c_str(), g_curPath.c_str(), MOVEFILE_REPLACE_EXISTING)) {
        DeleteFileW(tmp.c_str());
        MessageBoxW(g_hwnd, L"Could not overwrite the original file.", L"zat", MB_OK | MB_ICONERROR);
        return;
    }
    g_strokes.clear();
    StartLoad(g_curPath);
}

static void SaveMenu(const RECT& anchor) {
    HMENU m = CreatePopupMenu();
    AppendMenuW(m, MF_STRING, 1, L"Save as new file...");
    AppendMenuW(m, MF_STRING, 2, L"Overwrite original...");
    POINT pt = {anchor.left, anchor.bottom + 2};
    ClientToScreen(g_hwnd, &pt);
    int cmd = TrackPopupMenu(m, TPM_RETURNCMD | TPM_LEFTALIGN | TPM_TOPALIGN, pt.x, pt.y, 0, g_hwnd, nullptr);
    DestroyMenu(m);
    if (cmd == 1) SaveAsNew();
    else if (cmd == 2) OverwriteOriginal();
}

static void UndoStroke() {
    if (!g_strokes.empty()) {
        g_strokes.pop_back();
        InvalidateRect(g_hwnd, nullptr, FALSE);
    }
}
static void ClearStrokes() {
    if (g_strokes.empty()) return;
    g_strokes.clear();
    InvalidateRect(g_hwnd, nullptr, FALSE);
}
static void ToggleTheme() {
    g_dark = !g_dark;
    SaveTheme();
    ApplyTitleBarTheme();
    InvalidateBase();
}
static void ToggleAnnot() {
    if (g_animated || !g_w) return;
    g_annot = !g_annot;
    g_drawing = false;
    InvalidateRect(g_hwnd, nullptr, FALSE);
}

static void DoCommand(int id, const RECT& r) {
    switch (id) {
        case B_THEME: ToggleTheme(); break;
        case B_PEN: ToggleAnnot(); break;
        case B_SAVE: SaveMenu(r); break;
        case B_RED: case B_GREEN: case B_YELLOW: g_color = id - B_RED; InvalidateRect(g_hwnd, nullptr, FALSE); break;
        case B_TMINUS: if (g_thick > 0) g_thick--; InvalidateRect(g_hwnd, nullptr, FALSE); break;
        case B_TPLUS: if (g_thick < kThickCount - 1) g_thick++; InvalidateRect(g_hwnd, nullptr, FALSE); break;
        case B_UNDO: UndoStroke(); break;
        case B_CLEAR: ClearStrokes(); break;
        case B_PREV: Step(-1); break;
        case B_NEXT: Step(1); break;
        case B_ZIN: ZoomCenter(1.25); break;
        case B_ZOUT: ZoomCenter(1 / 1.25); break;
        case B_ROT: Rotate90(); break;
        case B_FIT: ApplyFit(); UpdateTitle(); InvalidateBase(); break;
        default: break;
    }
}

// --------------------------------------------------------------- painting
static void FreeBuffers() {
    if (g_baseDC) {
        SelectObject(g_baseDC, g_baseOld);
        DeleteDC(g_baseDC);
    }
    if (g_backDC) {
        SelectObject(g_backDC, g_backOld);
        DeleteDC(g_backDC);
    }
    if (g_baseBmp) DeleteObject(g_baseBmp);
    if (g_backBmp) DeleteObject(g_backBmp);
    g_baseDC = g_backDC = nullptr;
    g_baseBmp = g_backBmp = nullptr;
    g_bufW = g_bufH = 0;
    g_baseValid = false;
}
static void EnsureBuffers(HDC ref, int w, int h) {
    if (g_baseDC && g_bufW == w && g_bufH == h) return;
    FreeBuffers();
    g_baseDC = CreateCompatibleDC(ref);
    g_backDC = CreateCompatibleDC(ref);
    g_baseBmp = CreateCompatibleBitmap(ref, w, h);
    g_backBmp = CreateCompatibleBitmap(ref, w, h);
    g_baseOld = (HBITMAP)SelectObject(g_baseDC, g_baseBmp);
    g_backOld = (HBITMAP)SelectObject(g_backDC, g_backBmp);
    g_bufW = w;
    g_bufH = h;
    g_baseValid = false;
}

static void ImageTransform(Graphics& g) {
    double cw, ch;
    ClientSize(cw, ch);
    g.TranslateTransform((REAL)(cw / 2 + g_panx), (REAL)(ch / 2 + g_pany));
    g.RotateTransform((REAL)(g_rot * 90));
    g.ScaleTransform((REAL)g_scale, (REAL)g_scale);
    g.TranslateTransform(-(REAL)(g_w / 2.0), -(REAL)(g_h / 2.0));
}

static void RenderBase(int w, int h) {
    Graphics g(g_baseDC);
    Color bg = g_dark ? Color(255, 24, 25, 28) : Color(255, 238, 239, 242);
    g.Clear(bg);
    if (g_bmp && g_w) {
        if (g_interacting || g_animated) g.SetInterpolationMode(InterpolationModeBilinear);
        else g.SetInterpolationMode(g_scale < 1 ? InterpolationModeHighQualityBilinear : InterpolationModeHighQualityBicubic);
        g.SetPixelOffsetMode(PixelOffsetModeHalf);
        ImageTransform(g);
        ImageAttributes ia;
        ia.SetWrapMode(WrapModeTileFlipXY);
        g.DrawImage(g_bmp, RectF(0, 0, (REAL)g_w, (REAL)g_h), 0, 0, (REAL)g_w, (REAL)g_h, UnitPixel, &ia);
    } else if (g_failed || (!g_bmp && !g_loading)) {
        Font f(L"Segoe UI", Sf(15), FontStyleRegular, UnitPixel);
        SolidBrush tb(g_dark ? Color(255, 160, 164, 172) : Color(255, 90, 94, 102));
        StringFormat sf;
        sf.SetAlignment(StringAlignmentCenter);
        sf.SetLineAlignment(StringAlignmentCenter);
        g.SetTextRenderingHint(TextRenderingHintClearTypeGridFit);
        g.DrawString(g_failed ? L"This image could not be opened.\nThe format may need a Windows codec (HEIC/AVIF)." : L"", -1, &f,
                     RectF(0, 0, (REAL)w, (REAL)h), &sf, &tb);
    }
    g_baseValid = true;
}

static void Paint() {
    PAINTSTRUCT ps;
    HDC hdc = BeginPaint(g_hwnd, &ps);
    RECT rc;
    GetClientRect(g_hwnd, &rc);
    int w = rc.right, h = rc.bottom;
    if (w > 0 && h > 0) {
        EnsureBuffers(hdc, w, h);
        if (!g_baseValid) RenderBase(w, h);
        BitBlt(g_backDC, 0, 0, w, h, g_baseDC, 0, 0, SRCCOPY);
        Graphics g(g_backDC);
        if (g_bmp && (!g_strokes.empty() || g_drawing)) {
            GraphicsState st = g.Save();
            g.SetPixelOffsetMode(PixelOffsetModeHalf);
            ImageTransform(g);
            DrawStrokesTo(g, g_strokes, g_drawing ? &g_cur : nullptr);
            g.Restore(st);
        }
        g.SetSmoothingMode(SmoothingModeAntiAlias);
        std::vector<Btn> btns;
        Bars bars;
        Layout(btns, bars);
        PaintBar(g, bars.tl);
        if (bars.hasTR) PaintBar(g, bars.tr);
        PaintBar(g, bars.bot);
        Color fg = g_dark ? Color(255, 232, 234, 238) : Color(255, 40, 44, 52);
        for (auto& b : btns) {
            if (b.id != B_TDOT && b.enabled && (b.id == g_hover || b.active)) {
                int a = b.id == g_pressed ? 55 : (b.active ? 40 : 28);
                SolidBrush hb(g_dark ? Color(a, 255, 255, 255) : Color(a, 0, 0, 0));
                FillRound(g, hb, RectF((REAL)b.r.left, (REAL)b.r.top, (REAL)(b.r.right - b.r.left), (REAL)(b.r.bottom - b.r.top)), Sf(6));
            }
            DrawIconFor(g, b, fg);
        }
        BitBlt(hdc, 0, 0, w, h, g_backDC, 0, 0, SRCCOPY);
    }
    EndPaint(g_hwnd, &ps);
}

// ---------------------------------------------------------- fullscreen
static void ToggleFullscreen() {
    DWORD st = GetWindowLongW(g_hwnd, GWL_STYLE);
    if (!g_fullscreen) {
        g_wp.length = sizeof(g_wp);
        GetWindowPlacement(g_hwnd, &g_wp);
        MONITORINFO mi = {sizeof(mi)};
        GetMonitorInfoW(MonitorFromWindow(g_hwnd, MONITOR_DEFAULTTONEAREST), &mi);
        SetWindowLongW(g_hwnd, GWL_STYLE, st & ~WS_OVERLAPPEDWINDOW);
        SetWindowPos(g_hwnd, HWND_TOP, mi.rcMonitor.left, mi.rcMonitor.top, mi.rcMonitor.right - mi.rcMonitor.left,
                     mi.rcMonitor.bottom - mi.rcMonitor.top, SWP_NOOWNERZORDER | SWP_FRAMECHANGED);
        g_fullscreen = true;
    } else {
        SetWindowLongW(g_hwnd, GWL_STYLE, st | WS_OVERLAPPEDWINDOW);
        SetWindowPlacement(g_hwnd, &g_wp);
        SetWindowPos(g_hwnd, nullptr, 0, 0, 0, 0, SWP_NOMOVE | SWP_NOSIZE | SWP_NOZORDER | SWP_NOOWNERZORDER | SWP_FRAMECHANGED);
        g_fullscreen = false;
    }
}

// ----------------------------------------------------- 7. window procedure
static void OpenFiles(HDROP drop) {
    UINT n = DragQueryFileW(drop, 0xFFFFFFFF, nullptr, 0);
    std::vector<std::wstring> v;
    for (UINT i = 0; i < n; i++) {
        wchar_t p[MAX_PATH * 2];
        if (DragQueryFileW(drop, i, p, MAX_PATH * 2)) v.push_back(p);
    }
    DragFinish(drop);
    if (v.empty() || !ConfirmDiscard()) return;
    BuildList(v);
    if (g_idx >= 0) StartLoad(g_files[g_idx]);
}

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_ERASEBKGND: return 1;
        case WM_PAINT: Paint(); return 0;
        case WM_SIZE:
            if (wp != SIZE_MINIMIZED) {
                if (g_fit) ApplyFit();
                else ClampPan();
                g_baseValid = false;
                UpdateTitle();
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        case WM_GETMINMAXINFO: {
            MINMAXINFO* m = (MINMAXINFO*)lp;
            m->ptMinTrackSize.x = S(360);
            m->ptMinTrackSize.y = S(260);
            return 0;
        }
        case WM_DPICHANGED: {
            g_dpi = HIWORD(wp);
            RECT* r = (RECT*)lp;
            SetWindowPos(hwnd, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top, SWP_NOZORDER | SWP_NOACTIVATE);
            return 0;
        }
        case WM_LOADED: OnLoaded((LoadResult*)lp); return 0;
        case WM_DROPFILES: OpenFiles((HDROP)wp); return 0;
        case WM_TIMER:
            if (wp == T_ANIM) {
                KillTimer(hwnd, T_ANIM);
                if (IsIconic(hwnd)) SetTimer(hwnd, T_ANIM, 200, nullptr);
                else AnimStep();
            } else if (wp == T_IDLE) {
                KillTimer(hwnd, T_IDLE);
                g_interacting = false;
                InvalidateBase();
            }
            return 0;
        case WM_SETCURSOR:
            if (LOWORD(lp) == HTCLIENT) {
                POINT p;
                GetCursorPos(&p);
                ScreenToClient(hwnd, &p);
                std::vector<Btn> v;
                Bars bars;
                Layout(v, bars);
                LPCWSTR c = IDC_ARROW;
                if (g_panning) c = IDC_SIZEALL;
                else if (!InBars(bars, p.x, p.y) && g_annot && g_w) c = IDC_CROSS;
                else if (HitTest(v, p.x, p.y) >= 0) c = IDC_HAND;
                SetCursor(LoadCursorW(nullptr, c));
                return TRUE;
            }
            break;
        case WM_MOUSEMOVE: {
            int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
            if (!g_trackLeave) {
                TRACKMOUSEEVENT t = {sizeof(t), TME_LEAVE, hwnd, 0};
                TrackMouseEvent(&t);
                g_trackLeave = true;
            }
            if (g_drawing) {
                PointF p = ScreenToImage(x, y);
                PointF q = g_cur.pts.back();
                float minD = 1.5f / (float)g_scale;
                if (std::hypot(p.X - q.X, p.Y - q.Y) >= minD) {
                    g_cur.pts.push_back(p);
                    InvalidateRect(hwnd, nullptr, FALSE);
                }
            } else if (g_panning) {
                g_panx += x - g_last.x;
                g_pany += y - g_last.y;
                g_last.x = x;
                g_last.y = y;
                g_fit = g_fit && false;
                ClampPan();
                g_interacting = true;
                SetTimer(hwnd, T_IDLE, 140, nullptr);
                InvalidateBase();
            } else {
                std::vector<Btn> v;
                Bars bars;
                Layout(v, bars);
                int hv = HitTest(v, x, y);
                if (hv != g_hover) {
                    g_hover = hv;
                    InvalidateRect(hwnd, nullptr, FALSE);
                }
            }
            return 0;
        }
        case WM_MOUSELEAVE:
            g_trackLeave = false;
            if (g_hover != -1) {
                g_hover = -1;
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        case WM_LBUTTONDOWN:
        case WM_LBUTTONDBLCLK: {
            int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
            std::vector<Btn> v;
            Bars bars;
            Layout(v, bars);
            int id = HitTest(v, x, y);
            SetFocus(hwnd);
            if (id >= 0) {
                g_pressed = id;
                SetCapture(hwnd);
                InvalidateRect(hwnd, nullptr, FALSE);
                return 0;
            }
            if (InBars(bars, x, y) || !g_w) return 0;
            if (g_annot) {
                g_cur = Stroke();
                g_cur.color = g_color;
                g_cur.width = (float)(kThickLevels[g_thick] / g_scale);
                g_cur.pts.push_back(ScreenToImage(x, y));
                g_drawing = true;
                SetCapture(hwnd);
                InvalidateRect(hwnd, nullptr, FALSE);
            } else if (msg == WM_LBUTTONDBLCLK) {
                if (g_fit && FitScale() < 1) ZoomAt(1.0, x, y);
                else {
                    ApplyFit();
                    UpdateTitle();
                    InvalidateBase();
                }
            } else {
                g_panning = true;
                g_last.x = x;
                g_last.y = y;
                SetCapture(hwnd);
            }
            return 0;
        }
        case WM_RBUTTONDOWN:
        case WM_MBUTTONDOWN:
            if (g_w && !g_drawing) {
                g_panning = true;
                g_last.x = GET_X_LPARAM(lp);
                g_last.y = GET_Y_LPARAM(lp);
                SetCapture(hwnd);
            }
            return 0;
        case WM_RBUTTONUP:
        case WM_MBUTTONUP:
            if (g_panning) {
                g_panning = false;
                ReleaseCapture();
            }
            return 0;
        case WM_LBUTTONUP: {
            int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
            if (g_pressed >= 0) {
                std::vector<Btn> v;
                Bars bars;
                Layout(v, bars);
                int id = g_pressed;
                g_pressed = -1;
                ReleaseCapture();
                if (HitTest(v, x, y) == id) {
                    for (auto& b : v)
                        if (b.id == id && b.enabled) {
                            DoCommand(id, b.r);
                            break;
                        }
                }
                InvalidateRect(hwnd, nullptr, FALSE);
            } else if (g_drawing) {
                g_drawing = false;
                ReleaseCapture();
                g_strokes.push_back(g_cur);
                g_cur = Stroke();
                InvalidateRect(hwnd, nullptr, FALSE);
            } else if (g_panning) {
                g_panning = false;
                ReleaseCapture();
            }
            return 0;
        }
        case WM_MOUSEWHEEL: {
            POINT p = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            ScreenToClient(hwnd, &p);
            int delta = GET_WHEEL_DELTA_WPARAM(wp);
            if (g_annot && (GET_KEYSTATE_WPARAM(wp) & MK_CONTROL)) {
                g_thick = std::max(0, std::min(kThickCount - 1, g_thick + (delta > 0 ? 1 : -1)));
                InvalidateRect(hwnd, nullptr, FALSE);
            } else {
                ZoomAt(g_scale * std::pow(1.0016, delta), p.x, p.y);
            }
            return 0;
        }
        case WM_KEYDOWN: {
            bool ctrl = (GetKeyState(VK_CONTROL) & 0x8000) != 0;
            switch (wp) {
                case VK_LEFT: case VK_PRIOR: Step(-1); break;
                case VK_RIGHT: case VK_NEXT: case VK_SPACE: Step(1); break;
                case VK_HOME: GoTo(0); break;
                case VK_END: GoTo((int)g_files.size() - 1); break;
                case VK_ADD: case VK_OEM_PLUS: ZoomCenter(1.25); break;
                case VK_SUBTRACT: case VK_OEM_MINUS: ZoomCenter(1 / 1.25); break;
                case '0': ApplyFit(); UpdateTitle(); InvalidateBase(); break;
                case '1': { double cw, ch; ClientSize(cw, ch); ZoomAt(1.0, cw / 2, ch / 2); break; }
                case 'R': Rotate90(); break;
                case 'T': ToggleTheme(); break;
                case 'P': ToggleAnnot(); break;
                case 'Z': if (ctrl && g_annot) UndoStroke(); break;
                case 'S': if (ctrl) SaveAsNew(); break;
                case VK_F11: ToggleFullscreen(); break;
                case VK_OEM_4: g_thick = std::max(0, g_thick - 1); InvalidateRect(hwnd, nullptr, FALSE); break;  // [
                case VK_OEM_6: g_thick = std::min(kThickCount - 1, g_thick + 1); InvalidateRect(hwnd, nullptr, FALSE); break;  // ]
                case VK_ESCAPE:
                    if (g_annot) ToggleAnnot();
                    else if (g_fullscreen) ToggleFullscreen();
                    else PostMessageW(hwnd, WM_CLOSE, 0, 0);
                    break;
                default: break;
            }
            return 0;
        }
        case WM_CLOSE:
            if (!ConfirmDiscard()) return 0;
            DestroyWindow(hwnd);
            return 0;
        case WM_DESTROY:
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

// ---------------------------------------------------------------- 8. main
static std::wstring PickFile() {
    std::wstring filter = L"Images";
    filter.push_back(0);
    for (size_t i = 0; i < sizeof(kExts) / sizeof(kExts[0]); i++) {
        if (i) filter += L";";
        filter += std::wstring(L"*") + kExts[i];
    }
    filter.push_back(0);
    filter += L"All files";
    filter.push_back(0);
    filter += L"*.*";
    filter.push_back(0);
    filter.push_back(0);
    wchar_t file[MAX_PATH * 2] = {0};
    OPENFILENAMEW ofn = {};
    ofn.lStructSize = sizeof(ofn);
    ofn.lpstrFilter = filter.c_str();
    ofn.lpstrFile = file;
    ofn.nMaxFile = MAX_PATH * 2;
    ofn.lpstrTitle = L"zat - open image";
    ofn.Flags = OFN_FILEMUSTEXIST | OFN_PATHMUSTEXIST | OFN_NOCHANGEDIR;
    if (GetOpenFileNameW(&ofn)) return file;
    return L"";
}

int WINAPI WinMain(HINSTANCE hInst, HINSTANCE, LPSTR, int nShow) {
    g_inst = hInst;
    CoInitializeEx(nullptr, COINIT_APARTMENTTHREADED);
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    std::vector<std::wstring> args;
    for (int i = 1; argv && i < argc; i++)
        if (argv[i][0] != L'-' && !(argv[i][0] == L'/' && argv[i][1] && !wcschr(argv[i] + 1, L'\\') && !wcschr(argv[i] + 1, L':'))) args.push_back(argv[i]);
    if (argv) LocalFree(argv);

    BuildList(args);
    if (g_idx < 0) {
        std::wstring f = PickFile();
        if (f.empty()) return 0;
        BuildList({f});
        if (g_idx < 0) return 0;
    }

    if (FAILED(CoCreateInstance(CLSID_WICImagingFactory, nullptr, CLSCTX_INPROC_SERVER, IID_PPV_ARGS(&g_wic)))) {
        MessageBoxW(nullptr, L"Windows Imaging Component is not available.", L"zat", MB_OK | MB_ICONERROR);
        return 1;
    }
    GdiplusStartupInput gsi;
    GdiplusStartup(&g_gdipToken, &gsi, nullptr);
    LoadTheme();

    WNDCLASSEXW wc = {sizeof(wc)};
    wc.style = CS_DBLCLKS;
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInst;
    wc.hIcon = LoadIconW(hInst, MAKEINTRESOURCEW(1));
    wc.hIconSm = wc.hIcon;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.lpszClassName = kClassName;
    RegisterClassExW(&wc);

    g_dpi = QueryDpi(nullptr);
    int w = S(1000), h = S(680);
    g_hwnd = CreateWindowExW(WS_EX_ACCEPTFILES, kClassName, kAppName, WS_OVERLAPPEDWINDOW | WS_CLIPCHILDREN, CW_USEDEFAULT, CW_USEDEFAULT, w, h,
                             nullptr, nullptr, hInst, nullptr);
    if (!g_hwnd) return 1;
    g_dpi = QueryDpi(g_hwnd);
    ApplyTitleBarTheme();
    ShowWindow(g_hwnd, nShow);
    UpdateWindow(g_hwnd);
    StartLoad(g_files[g_idx]);

    MSG m;
    while (GetMessageW(&m, nullptr, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    FreeImage();
    FreeBuffers();
    SafeRelease(g_wic);
    GdiplusShutdown(g_gdipToken);
    CoUninitialize();
    return (int)m.wParam;
}
