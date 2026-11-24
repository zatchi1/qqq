// zatchi - shows a chosen game as "Playing" in Discord.
//
// How it works: Discord detects games by the file name of running processes.
// zatchi copies itself to a temporary folder under the executable name of the chosen game
// (taken from Discord's public list of detectable games) and starts that copy in "stub" mode:
// an invisible process that does nothing and exits when zatchi stops it or exits.
//
// C++17, Win32 + GDI+. Dark UI, tray icon, single instance.

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
#include <shlobj.h>
#include <shlwapi.h>
#include <commctrl.h>
#include <cstdlib>
#include <algorithm>
#include <cmath>
#include <cstdint>
#include <cstring>
#include <string>
#include <vector>
using std::max;
using std::min;
#include <gdiplus.h>
using namespace Gdiplus;

// ------------------------------------------------------------------ constants
static const wchar_t* kMainClass = L"zatchiMainWindow";
static const wchar_t* kStubClass = L"zatchiStubWindow";
static const wchar_t* kMutexName = L"zatchi_single_instance_mutex";
enum { WM_TRAY = WM_APP + 1 };
enum { ID_SHOW = 1001, ID_STOP, ID_AUTO, ID_MIN, ID_EXIT };
enum { T_TICK = 1 };
enum { BTN_NONE = 0, BTN_PLAY, BTN_STOP };

struct Game {
    std::wstring name, exe, hay, args;
    int pop;
};

// ------------------------------------------------------------------- globals
static HINSTANCE g_inst;
static HWND g_hwnd, g_edit;
static ULONG_PTR g_gdip;
static int g_dpi = 96;
static UINT g_msgShow, g_msgTaskbar;
static WNDPROC g_editProc;

static std::vector<Game> g_games;
static std::vector<int> g_view;  // indices into g_games after filtering
static int g_selGame = -1;
static int g_hoverRow = -1;
static int g_scroll = 0;
static bool g_dragThumb = false;
static int g_dragOffset = 0;
static int g_hotBtn = BTN_NONE, g_pressBtn = BTN_NONE;
static bool g_editFocus = false;
static bool g_trackLeave = false;
static bool g_exiting = false;
static bool g_balloonShown = false;

static bool g_playing = false;
static int g_playGame = -1;
static PROCESS_INFORMATION g_pi = {};
static std::wstring g_playDir;
static ULONGLONG g_playStart = 0;

static HDC g_mdc = nullptr;
static HBITMAP g_mbmp = nullptr, g_mold = nullptr;
static int g_mw = 0, g_mh = 0;

static FontFamily* g_ff = nullptr;
static Font *g_fTitle = nullptr, *g_fName = nullptr, *g_fSub = nullptr, *g_fBtn = nullptr, *g_fSmall = nullptr;
static HFONT g_hEditFont = nullptr;
static HBRUSH g_editBrush = nullptr;

static int S(int v) { return MulDiv(v, g_dpi, 96); }
static float Sf(float v) { return v * g_dpi / 96.0f; }

// theme
static const Color cBg(255, 18, 19, 22), cPanel(255, 28, 30, 35), cHover(255, 37, 40, 47), cSel(255, 45, 50, 64),
    cLine(255, 48, 51, 58), cText(255, 235, 237, 241), cMuted(255, 140, 146, 158), cAccent(255, 88, 101, 242),
    cGreen(255, 35, 165, 90), cRed(255, 237, 66, 69), cGold(255, 250, 204, 21);
static const COLORREF kBgRef = RGB(28, 30, 35);

// ------------------------------------------------------------------- helpers
static std::wstring Utf8ToW(const char* s, size_t n) {
    if (!n) return L"";
    int len = MultiByteToWideChar(CP_UTF8, 0, s, (int)n, nullptr, 0);
    std::wstring w(len, 0);
    MultiByteToWideChar(CP_UTF8, 0, s, (int)n, &w[0], len);
    return w;
}
static std::wstring LowerW(std::wstring s) {
    if (!s.empty()) CharLowerBuffW(&s[0], (DWORD)s.size());
    return s;
}
static std::wstring SelfPath() {
    wchar_t p[MAX_PATH * 2];
    DWORD n = GetModuleFileNameW(nullptr, p, MAX_PATH * 2);
    return std::wstring(p, n);
}
static std::wstring RunRoot() {
    wchar_t p[MAX_PATH];
    if (FAILED(SHGetFolderPathW(nullptr, CSIDL_LOCAL_APPDATA, nullptr, 0, p))) return L"";
    return std::wstring(p) + L"\\zatchi\\run";
}
static void DeleteTree(const std::wstring& dir) {
    if (dir.empty() || GetFileAttributesW(dir.c_str()) == INVALID_FILE_ATTRIBUTES) return;
    std::wstring from = dir;
    from.push_back(0);  // double-null terminated
    SHFILEOPSTRUCTW op = {};
    op.wFunc = FO_DELETE;
    op.pFrom = from.c_str();
    op.fFlags = FOF_NOCONFIRMATION | FOF_NOERRORUI | FOF_SILENT;
    SHFileOperationW(&op);
}

static void RegSetStr(const wchar_t* sub, const wchar_t* name, const std::wstring& v) {
    HKEY k;
    if (RegCreateKeyExW(HKEY_CURRENT_USER, sub, 0, nullptr, 0, KEY_SET_VALUE, nullptr, &k, nullptr) == ERROR_SUCCESS) {
        RegSetValueExW(k, name, 0, REG_SZ, (const BYTE*)v.c_str(), (DWORD)((v.size() + 1) * sizeof(wchar_t)));
        RegCloseKey(k);
    }
}
static std::wstring RegGetStr(const wchar_t* sub, const wchar_t* name) {
    HKEY k;
    std::wstring out;
    if (RegOpenKeyExW(HKEY_CURRENT_USER, sub, 0, KEY_QUERY_VALUE, &k) == ERROR_SUCCESS) {
        wchar_t buf[1024];
        DWORD sz = sizeof(buf), type = 0;
        if (RegQueryValueExW(k, name, nullptr, &type, (BYTE*)buf, &sz) == ERROR_SUCCESS && type == REG_SZ) out = buf;
        RegCloseKey(k);
    }
    return out;
}
static const wchar_t* kRunKey = L"Software\\Microsoft\\Windows\\CurrentVersion\\Run";
static bool AutoStartEnabled() { return !RegGetStr(kRunKey, L"zatchi").empty(); }
// "1" = the little game window is started minimized (default: shown normally, Discord may ignore hidden processes)
static bool MinStub() { return RegGetStr(L"Software\\zatchi", L"MinimizeGameWindow") == L"1"; }
static void SetAutoStart(bool on) {
    if (on) {
        RegSetStr(kRunKey, L"zatchi", L"\"" + SelfPath() + L"\" --background");
    } else {
        HKEY k;
        if (RegOpenKeyExW(HKEY_CURRENT_USER, kRunKey, 0, KEY_SET_VALUE, &k) == ERROR_SUCCESS) {
            RegDeleteValueW(k, L"zatchi");
            RegCloseKey(k);
        }
    }
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
static void DarkTitleBar(HWND h) {
    typedef HRESULT(WINAPI * PFN)(HWND, DWORD, LPCVOID, DWORD);
    HMODULE dwm = LoadLibraryW(L"dwmapi.dll");
    if (!dwm) return;
    PFN fn = (PFN)GetProcAddress(dwm, "DwmSetWindowAttribute");
    if (fn) {
        BOOL v = TRUE;
        fn(h, 20, &v, sizeof(v));
        fn(h, 19, &v, sizeof(v));
    }
    FreeLibrary(dwm);
}

// --------------------------------------------------------------- games data
static void LoadGames() {
    HRSRC r = FindResourceW(nullptr, MAKEINTRESOURCEW(2), RT_RCDATA);
    if (!r) return;
    HGLOBAL h = LoadResource(nullptr, r);
    if (!h) return;
    const char* p = (const char*)LockResource(h);
    size_t n = SizeofResource(nullptr, r);
    size_t i = 0;
    g_games.reserve(20000);
    while (i < n) {
        size_t e = i;
        while (e < n && p[e] != '\n') e++;
        size_t l = e;
        if (l > i && p[l - 1] == '\r') l--;
        size_t t1 = i;
        while (t1 < l && p[t1] != '\t') t1++;
        size_t t2 = t1 + 1;
        while (t2 < l && p[t2] != '\t') t2++;
        size_t t3 = t2 + 1;
        while (t3 < l && p[t3] != '\t') t3++;
        if (t1 < l && t2 <= l) {
            Game g;
            g.name = Utf8ToW(p + i, t1 - i);
            g.exe = Utf8ToW(p + t1 + 1, t2 - t1 - 1);
            size_t pe = t3 < l ? t3 : l;
            g.pop = t2 < pe ? atoi(std::string(p + t2 + 1, pe - t2 - 1).c_str()) : -1;
            if (t3 < l) g.args = Utf8ToW(p + t3 + 1, l - t3 - 1);
            g.hay = LowerW(g.name) + L"\n" + LowerW(g.exe);
            if (!g.name.empty() && !g.exe.empty()) g_games.push_back(std::move(g));
        }
        i = e + 1;
    }
}

static void Refilter() {
    wchar_t buf[256] = {0};
    GetWindowTextW(g_edit, buf, 255);
    std::wstring q = LowerW(buf);
    std::vector<std::wstring> tok;
    size_t pos = 0;
    while (pos < q.size()) {
        while (pos < q.size() && q[pos] == L' ') pos++;
        size_t e = pos;
        while (e < q.size() && q[e] != L' ') e++;
        if (e > pos) tok.push_back(q.substr(pos, e - pos));
        pos = e;
    }
    g_view.clear();
    for (size_t i = 0; i < g_games.size(); i++) {
        bool ok = true;
        for (auto& t : tok)
            if (g_games[i].hay.find(t) == std::wstring::npos) {
                ok = false;
                break;
            }
        if (ok) g_view.push_back((int)i);
    }
    g_scroll = 0;
    g_hoverRow = -1;
    if (g_selGame >= 0 && std::find(g_view.begin(), g_view.end(), g_selGame) == g_view.end()) g_selGame = -1;
    if (g_selGame < 0 && !g_view.empty() && !tok.empty()) g_selGame = g_view[0];
    InvalidateRect(g_hwnd, nullptr, FALSE);
}

// ---------------------------------------------------------------- layout
struct Lay {
    RECT title, search, list, status, play, stop;
};
static Lay Layout() {
    RECT rc;
    GetClientRect(g_hwnd, &rc);
    Lay l;
    int m = S(16);
    l.title = {m, S(12), rc.right - m, S(62)};
    l.search = {m, S(66), rc.right - m, S(66) + S(42)};
    int ft = rc.bottom - S(108);
    l.status = {m, ft + S(4), rc.right - m, ft + S(30)};
    int by = ft + S(38), bh = S(48), gap = S(10);
    int totalW = rc.right - 2 * m;
    int pw = (int)((totalW - gap) * 0.62);
    l.play = {m, by, m + pw, by + bh};
    l.stop = {m + pw + gap, by, rc.right - m, by + bh};
    l.list = {m, l.search.bottom + S(12), rc.right - m, ft - S(4)};
    return l;
}
static int RowH() { return S(54); }
static int ContentH() { return (int)g_view.size() * RowH(); }
static int ViewH(const Lay& l) { return l.list.bottom - l.list.top; }
static void ClampScroll(const Lay& l) {
    int maxS = std::max(0, ContentH() - ViewH(l));
    g_scroll = std::max(0, std::min(maxS, g_scroll));
}
static void PlaceEdit() {
    Lay l = Layout();
    int h = S(22);
    int x = l.search.left + S(42), w = (l.search.right - S(14)) - x;
    int y = l.search.top + ((l.search.bottom - l.search.top) - h) / 2;
    SetWindowPos(g_edit, nullptr, x, y, std::max(10, w), h, SWP_NOZORDER | SWP_NOACTIVATE);
}
static void EnsureVisible(int row) {
    Lay l = Layout();
    int top = row * RowH(), bot = top + RowH();
    if (top < g_scroll) g_scroll = top;
    else if (bot > g_scroll + ViewH(l)) g_scroll = bot - ViewH(l);
    ClampScroll(l);
}
static int RowOfGame(int game) {
    auto it = std::find(g_view.begin(), g_view.end(), game);
    return it == g_view.end() ? -1 : (int)(it - g_view.begin());
}

// ---------------------------------------------------------------- play / stop
static std::wstring Elapsed() {
    ULONGLONG s = (GetTickCount64() - g_playStart) / 1000;
    wchar_t b[32];
    wsprintfW(b, L"%02u:%02u:%02u", (unsigned)(s / 3600), (unsigned)((s / 60) % 60), (unsigned)(s % 60));
    return b;
}
static void UpdateTray();

static void StopPlaying() {
    if (g_pi.hProcess) {
        TerminateProcess(g_pi.hProcess, 0);
        WaitForSingleObject(g_pi.hProcess, 3000);
        CloseHandle(g_pi.hProcess);
        CloseHandle(g_pi.hThread);
        g_pi = PROCESS_INFORMATION();
    }
    if (!g_playDir.empty()) DeleteTree(g_playDir);
    g_playDir.clear();
    g_playing = false;
    g_playGame = -1;
    KillTimer(g_hwnd, T_TICK);
    UpdateTray();
    InvalidateRect(g_hwnd, nullptr, FALSE);
}

static void StartPlaying(int gi) {
    if (gi < 0 || gi >= (int)g_games.size()) return;
    if (g_playing) StopPlaying();
    const Game& g = g_games[gi];
    std::wstring root = RunRoot();
    if (root.empty()) return;
    std::wstring rel = g.exe;
    for (auto& c : rel)
        if (c == L'/') c = L'\\';
    g_playDir = root + L"\\" + std::to_wstring(gi);
    std::wstring target = g_playDir + L"\\" + rel;
    if (target.size() >= MAX_PATH - 2) {
        MessageBoxW(g_hwnd, L"This game's executable path is too long.", L"zatchi", MB_OK | MB_ICONWARNING);
        g_playDir.clear();
        return;
    }
    std::wstring dir = target.substr(0, target.find_last_of(L'\\'));
    SHCreateDirectoryExW(nullptr, dir.c_str(), nullptr);
    if (!CopyFileW(SelfPath().c_str(), target.c_str(), FALSE)) {
        MessageBoxW(g_hwnd, L"Could not prepare the game process.", L"zatchi", MB_OK | MB_ICONERROR);
        DeleteTree(g_playDir);
        g_playDir.clear();
        return;
    }
    std::wstring cmd = L"\"" + target + L"\" --stub " + std::to_wstring(GetCurrentProcessId());
    if (MinStub()) cmd += L" --min";
    if (!g.args.empty()) cmd += L" " + g.args;  // some games are only detected with their own launch arguments
    STARTUPINFOW si = {sizeof(si)};
    PROCESS_INFORMATION pi = {};
    std::vector<wchar_t> cb(cmd.begin(), cmd.end());
    cb.push_back(0);
    if (!CreateProcessW(target.c_str(), cb.data(), nullptr, nullptr, FALSE, 0, nullptr, dir.c_str(), &si, &pi)) {
        MessageBoxW(g_hwnd, L"Could not start the game process.", L"zatchi", MB_OK | MB_ICONERROR);
        DeleteTree(g_playDir);
        g_playDir.clear();
        return;
    }
    g_pi = pi;
    g_playing = true;
    g_playGame = gi;
    g_playStart = GetTickCount64();
    SetTimer(g_hwnd, T_TICK, 1000, nullptr);
    RegSetStr(L"Software\\zatchi", L"Last", g.name);
    UpdateTray();
    InvalidateRect(g_hwnd, nullptr, FALSE);
}

// ---------------------------------------------------------------- tray
static void AddTray() {
    NOTIFYICONDATAW nid = {};
    nid.cbSize = sizeof(nid);
    nid.hWnd = g_hwnd;
    nid.uID = 1;
    nid.uFlags = NIF_ICON | NIF_MESSAGE | NIF_TIP;
    nid.uCallbackMessage = WM_TRAY;
    nid.hIcon = (HICON)LoadImageW(g_inst, MAKEINTRESOURCEW(1), IMAGE_ICON, GetSystemMetrics(SM_CXSMICON), GetSystemMetrics(SM_CYSMICON), 0);
    lstrcpynW(nid.szTip, L"zatchi", 128);
    Shell_NotifyIconW(NIM_ADD, &nid);
}
static void UpdateTray() {
    NOTIFYICONDATAW nid = {};
    nid.cbSize = sizeof(nid);
    nid.hWnd = g_hwnd;
    nid.uID = 1;
    nid.uFlags = NIF_TIP;
    std::wstring tip = g_playing && g_playGame >= 0 ? L"zatchi - playing " + g_games[g_playGame].name : L"zatchi";
    lstrcpynW(nid.szTip, tip.c_str(), 127);
    Shell_NotifyIconW(NIM_MODIFY, &nid);
}
static void RemoveTray() {
    NOTIFYICONDATAW nid = {};
    nid.cbSize = sizeof(nid);
    nid.hWnd = g_hwnd;
    nid.uID = 1;
    Shell_NotifyIconW(NIM_DELETE, &nid);
}
static void ShowMain() {
    ShowWindow(g_hwnd, IsIconic(g_hwnd) ? SW_RESTORE : SW_SHOW);
    SetForegroundWindow(g_hwnd);
    SetFocus(g_edit);
}
static void HideToTray() {
    ShowWindow(g_hwnd, SW_HIDE);
    if (!g_balloonShown) {
        g_balloonShown = true;
        NOTIFYICONDATAW nid = {};
        nid.cbSize = sizeof(nid);
        nid.hWnd = g_hwnd;
        nid.uID = 1;
        nid.uFlags = NIF_INFO;
        nid.dwInfoFlags = NIIF_INFO;
        lstrcpynW(nid.szInfoTitle, L"zatchi is still running", 64);
        lstrcpynW(nid.szInfo, L"Right-click the tray icon to open it or exit.", 256);
        Shell_NotifyIconW(NIM_MODIFY, &nid);
    }
}
static void TrayMenu() {
    HMENU m = CreatePopupMenu();
    AppendMenuW(m, MF_STRING, ID_SHOW, L"Open zatchi");
    if (g_playing && g_playGame >= 0) {
        std::wstring s = L"Stop playing: " + g_games[g_playGame].name;
        AppendMenuW(m, MF_STRING, ID_STOP, s.c_str());
    }
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING | (AutoStartEnabled() ? MF_CHECKED : 0), ID_AUTO, L"Start with Windows");
    AppendMenuW(m, MF_STRING | (MinStub() ? MF_CHECKED : 0), ID_MIN, L"Minimize the game window (next Play)");
    AppendMenuW(m, MF_SEPARATOR, 0, nullptr);
    AppendMenuW(m, MF_STRING, ID_EXIT, L"Exit");
    POINT pt;
    GetCursorPos(&pt);
    SetForegroundWindow(g_hwnd);
    int cmd = TrackPopupMenu(m, TPM_RETURNCMD | TPM_RIGHTBUTTON, pt.x, pt.y, 0, g_hwnd, nullptr);
    PostMessageW(g_hwnd, WM_NULL, 0, 0);
    DestroyMenu(m);
    switch (cmd) {
        case ID_SHOW: ShowMain(); break;
        case ID_STOP: StopPlaying(); break;
        case ID_AUTO: SetAutoStart(!AutoStartEnabled()); break;
        case ID_MIN: RegSetStr(L"Software\\zatchi", L"MinimizeGameWindow", MinStub() ? L"0" : L"1"); break;
        case ID_EXIT: g_exiting = true; DestroyWindow(g_hwnd); break;
    }
}

// ---------------------------------------------------------------- painting
static void EnsureFonts() {
    static int builtDpi = 0;
    if (builtDpi == g_dpi && g_fName) return;
    delete g_fTitle; delete g_fName; delete g_fSub; delete g_fBtn; delete g_fSmall;
    if (!g_ff) g_ff = new FontFamily(L"Segoe UI");
    g_fTitle = new Font(g_ff, Sf(24), FontStyleBold, UnitPixel);
    g_fName = new Font(g_ff, Sf(15), FontStyleRegular, UnitPixel);
    g_fSub = new Font(g_ff, Sf(12), FontStyleRegular, UnitPixel);
    g_fBtn = new Font(g_ff, Sf(16), FontStyleBold, UnitPixel);
    g_fSmall = new Font(g_ff, Sf(13), FontStyleRegular, UnitPixel);
    if (g_hEditFont) DeleteObject(g_hEditFont);
    g_hEditFont = CreateFontW(-S(16), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                              CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE, L"Segoe UI");
    if (g_edit) SendMessageW(g_edit, WM_SETFONT, (WPARAM)g_hEditFont, TRUE);
    builtDpi = g_dpi;
}
static void RoundPath(GraphicsPath& p, RectF r, float rad) {
    float d = rad * 2;
    p.AddArc(r.X, r.Y, d, d, 180, 90);
    p.AddArc(r.X + r.Width - d, r.Y, d, d, 270, 90);
    p.AddArc(r.X + r.Width - d, r.Y + r.Height - d, d, d, 0, 90);
    p.AddArc(r.X, r.Y + r.Height - d, d, d, 90, 90);
    p.CloseFigure();
}
static void FillRound(Graphics& g, const Brush& b, RectF r, float rad) {
    GraphicsPath p;
    RoundPath(p, r, rad);
    g.FillPath(&b, &p);
}
static void StrokeRound(Graphics& g, const Pen& pen, RectF r, float rad) {
    GraphicsPath p;
    RoundPath(p, r, rad);
    g.DrawPath(&pen, &p);
}
static RectF RF(const RECT& r) { return RectF((REAL)r.left, (REAL)r.top, (REAL)(r.right - r.left), (REAL)(r.bottom - r.top)); }
static void DrawLabel(Graphics& g, const wchar_t* t, const Font* f, RectF r, Color c, StringAlignment h = StringAlignmentNear,
                     StringAlignment v = StringAlignmentCenter) {
    StringFormat sf;
    sf.SetAlignment(h);
    sf.SetLineAlignment(v);
    sf.SetFormatFlags(StringFormatFlagsNoWrap);
    sf.SetTrimming(StringTrimmingEllipsisCharacter);
    SolidBrush b(c);
    g.DrawString(t, -1, f, r, &sf, &b);
}
static void DrawStar(Graphics& g, float cx, float cy, float r, Color c) {
    PointF p[10];
    for (int i = 0; i < 10; i++) {
        double a = -3.14159265 / 2 + i * 3.14159265 / 5;
        float rr = (i % 2 == 0) ? r : r * 0.45f;
        p[i] = PointF(cx + (float)std::cos(a) * rr, cy + (float)std::sin(a) * rr);
    }
    SolidBrush b(c);
    g.FillPolygon(&b, p, 10);
}

static void DrawButton(Graphics& g, const RECT& r, int which, bool enabled) {
    RectF rf = RF(r);
    bool hot = g_hotBtn == which, press = g_pressBtn == which && hot;
    Color base = which == BTN_PLAY ? cGreen : cRed;
    int a = enabled ? 255 : 70;
    if (which == BTN_PLAY) {
        int add = enabled ? (press ? -18 : (hot ? 18 : 0)) : 0;
        SolidBrush b(Color(a, (BYTE)std::max(0, std::min(255, base.GetR() + add)), (BYTE)std::max(0, std::min(255, base.GetG() + add)),
                           (BYTE)std::max(0, std::min(255, base.GetB() + add))));
        FillRound(g, b, rf, Sf(10));
    } else {
        SolidBrush b(Color(enabled ? (press ? 70 : (hot ? 50 : 30)) : 12, base.GetR(), base.GetG(), base.GetB()));
        FillRound(g, b, rf, Sf(10));
        Pen p(Color(enabled ? 255 : 80, base.GetR(), base.GetG(), base.GetB()), Sf(1.5f));
        StrokeRound(g, p, rf, Sf(10));
    }
    Color fg = which == BTN_PLAY ? Color(a, 255, 255, 255) : Color(enabled ? 255 : 90, base.GetR(), base.GetG(), base.GetB());
    float cx = rf.X + rf.Width / 2, cy = rf.Y + rf.Height / 2;
    SolidBrush fb(fg);
    float ic = Sf(8);
    if (which == BTN_PLAY) {
        PointF tri[3] = {PointF(cx - Sf(50), cy - ic), PointF(cx - Sf(50), cy + ic), PointF(cx - Sf(50) + ic * 1.6f, cy)};
        g.FillPolygon(&fb, tri, 3);
        DrawLabel(g, L"Play", g_fBtn, RectF(cx - Sf(30), rf.Y, Sf(100), rf.Height), fg, StringAlignmentNear);
    } else {
        g.FillRectangle(&fb, cx - Sf(40), cy - ic * 0.9f, ic * 1.8f, ic * 1.8f);
        DrawLabel(g, L"Stop", g_fBtn, RectF(cx - Sf(16), rf.Y, Sf(80), rf.Height), fg, StringAlignmentNear);
    }
}

static void Paint() {
    PAINTSTRUCT ps;
    HDC hdc = BeginPaint(g_hwnd, &ps);
    RECT rc;
    GetClientRect(g_hwnd, &rc);
    int w = rc.right, h = rc.bottom;
    if (w > 0 && h > 0) {
        if (!g_mdc || g_mw != w || g_mh != h) {
            if (g_mdc) {
                SelectObject(g_mdc, g_mold);
                DeleteObject(g_mbmp);
                DeleteDC(g_mdc);
            }
            g_mdc = CreateCompatibleDC(hdc);
            g_mbmp = CreateCompatibleBitmap(hdc, w, h);
            g_mold = (HBITMAP)SelectObject(g_mdc, g_mbmp);
            g_mw = w;
            g_mh = h;
        }
        EnsureFonts();
        Graphics g(g_mdc);
        g.SetSmoothingMode(SmoothingModeAntiAlias);
        g.SetTextRenderingHint(TextRenderingHintClearTypeGridFit);
        g.Clear(cBg);
        Lay l = Layout();

        // header
        DrawLabel(g, L"zatchi", g_fTitle, RF(l.title), cText, StringAlignmentNear, StringAlignmentNear);
        std::wstring cnt = std::to_wstring(g_view.size()) + (g_view.size() == 1 ? L" game" : L" games");
        DrawLabel(g, cnt.c_str(), g_fSmall, RF(l.title), cMuted, StringAlignmentFar, StringAlignmentNear);
        DrawLabel(g, L"Pick a game and press Play. Discord will show it as your activity.", g_fSub,
                 RectF((REAL)l.title.left, (REAL)l.title.top + Sf(32), (REAL)(l.title.right - l.title.left), Sf(18)), cMuted, StringAlignmentNear,
                 StringAlignmentNear);

        // search box
        {
            SolidBrush pb(cPanel);
            FillRound(g, pb, RF(l.search), Sf(10));
            Pen bp(g_editFocus ? cAccent : cLine, Sf(1.5f));
            StrokeRound(g, bp, RF(l.search), Sf(10));
            float cx = (float)l.search.left + Sf(22), cy = (float)(l.search.top + l.search.bottom) / 2 - Sf(1);
            Pen ip(cMuted, Sf(1.8f));
            ip.SetStartCap(LineCapRound);
            ip.SetEndCap(LineCapRound);
            g.DrawEllipse(&ip, cx - Sf(7), cy - Sf(7), Sf(11), Sf(11));
            g.DrawLine(&ip, cx + Sf(2.5f), cy + Sf(2.5f), cx + Sf(7.5f), cy + Sf(7.5f));
        }

        // list
        {
            ClampScroll(l);
            GraphicsState st = g.Save();
            g.SetClip(RF(l.list));
            int rh = RowH();
            int first = g_scroll / rh, last = std::min((int)g_view.size() - 1, (g_scroll + ViewH(l)) / rh);
            for (int row = first; row <= last; row++) {
                int gi = g_view[row];
                const Game& gm = g_games[gi];
                RectF rr((REAL)l.list.left, (REAL)(l.list.top + row * rh - g_scroll), (REAL)(l.list.right - l.list.left), (REAL)rh - Sf(4));
                bool sel = gi == g_selGame, hov = row == g_hoverRow, now = g_playing && gi == g_playGame;
                if (sel || hov) {
                    SolidBrush b(sel ? cSel : cHover);
                    FillRound(g, b, rr, Sf(8));
                }
                if (sel) {
                    SolidBrush ab(cAccent);
                    FillRound(g, ab, RectF(rr.X, rr.Y + Sf(10), Sf(3.5f), rr.Height - Sf(20)), Sf(2));
                }
                float tx = rr.X + Sf(16), tw = rr.Width - Sf(16) - Sf(52);
                DrawLabel(g, gm.name.c_str(), g_fName, RectF(tx, rr.Y + Sf(5), tw, Sf(22)), cText);
                DrawLabel(g, gm.exe.c_str(), g_fSub, RectF(tx, rr.Y + Sf(26), tw, Sf(18)), cMuted);
                if (now) {
                    SolidBrush gb(cGreen);
                    g.FillEllipse(&gb, rr.X + rr.Width - Sf(30), rr.Y + rr.Height / 2 - Sf(5), Sf(10), Sf(10));
                } else if (gm.pop >= 0) {
                    DrawStar(g, rr.X + rr.Width - Sf(24), rr.Y + rr.Height / 2, Sf(7), cGold);
                }
            }
            if (g_view.empty()) {
                DrawLabel(g, L"No games found", g_fName, RF(l.list), cMuted, StringAlignmentCenter, StringAlignmentCenter);
            }
            int ch = ContentH(), vh = ViewH(l);
            if (ch > vh) {
                float th = std::max(Sf(30), (float)vh * vh / ch);
                float ty = l.list.top + (float)g_scroll * (vh - th) / (float)(ch - vh);
                SolidBrush tb(g_dragThumb ? Color(255, 110, 116, 130) : Color(255, 70, 74, 84));
                FillRound(g, tb, RectF((REAL)l.list.right - Sf(7), ty, Sf(5), th), Sf(2.5f));
            }
            g.Restore(st);
        }

        // footer
        {
            std::wstring st;
            Color sc = cMuted;
            if (g_playing && g_playGame >= 0) {
                st = L"Playing: " + g_games[g_playGame].name + L"   " + Elapsed();
                sc = cGreen;
            } else {
                st = L"Not playing";
            }
            if (g_playing) {
                SolidBrush gb(cGreen);
                g.FillEllipse(&gb, (REAL)l.status.left, (REAL)(l.status.top + l.status.bottom) / 2 - Sf(4), Sf(8), Sf(8));
            }
            RectF sr = RF(l.status);
            sr.X += g_playing ? Sf(16) : 0;
            sr.Width -= g_playing ? Sf(16) : 0;
            DrawLabel(g, st.c_str(), g_fSmall, sr, sc);
            DrawButton(g, l.play, BTN_PLAY, g_selGame >= 0);
            DrawButton(g, l.stop, BTN_STOP, g_playing);
        }
        BitBlt(hdc, 0, 0, w, h, g_mdc, 0, 0, SRCCOPY);
    }
    EndPaint(g_hwnd, &ps);
}

// ---------------------------------------------------------------- input
static void MoveSelection(int delta) {
    if (g_view.empty()) return;
    int row = RowOfGame(g_selGame);
    if (row < 0) row = delta > 0 ? -1 : (int)g_view.size();
    row = std::max(0, std::min((int)g_view.size() - 1, row + delta));
    g_selGame = g_view[row];
    EnsureVisible(row);
    InvalidateRect(g_hwnd, nullptr, FALSE);
}
static void PlaySelected() {
    if (g_selGame >= 0) StartPlaying(g_selGame);
}
static int HitBtn(int x, int y) {
    Lay l = Layout();
    POINT p = {x, y};
    if (PtInRect(&l.play, p)) return BTN_PLAY;
    if (PtInRect(&l.stop, p)) return BTN_STOP;
    return BTN_NONE;
}
static int RowAt(int x, int y) {
    Lay l = Layout();
    POINT p = {x, y};
    if (!PtInRect(&l.list, p)) return -1;
    int row = (y - l.list.top + g_scroll) / RowH();
    return row >= 0 && row < (int)g_view.size() ? row : -1;
}
static bool ThumbRect(RECT& r) {
    Lay l = Layout();
    int ch = ContentH(), vh = ViewH(l);
    if (ch <= vh) return false;
    float th = std::max(Sf(30), (float)vh * vh / ch);
    float ty = l.list.top + (float)g_scroll * (vh - th) / (float)(ch - vh);
    r = {l.list.right - S(14), (int)ty, l.list.right, (int)(ty + th)};
    return true;
}

static LRESULT CALLBACK EditProc(HWND h, UINT msg, WPARAM wp, LPARAM lp) {
    switch (msg) {
        case WM_KEYDOWN:
            switch (wp) {
                case VK_UP: MoveSelection(-1); return 0;
                case VK_DOWN: MoveSelection(1); return 0;
                case VK_PRIOR: MoveSelection(-6); return 0;
                case VK_NEXT: MoveSelection(6); return 0;
                case VK_RETURN: PlaySelected(); return 0;
                case VK_ESCAPE:
                    if (GetWindowTextLengthW(h) > 0) SetWindowTextW(h, L"");
                    else HideToTray();
                    return 0;
            }
            break;
        case WM_CHAR:
            if (wp == VK_RETURN || wp == VK_ESCAPE) return 0;
            break;
    }
    return CallWindowProcW(g_editProc, h, msg, wp, lp);
}

static LRESULT CALLBACK WndProc(HWND hwnd, UINT msg, WPARAM wp, LPARAM lp) {
    if (msg == g_msgShow && g_msgShow) {
        ShowMain();
        return 0;
    }
    if (msg == g_msgTaskbar && g_msgTaskbar) {
        AddTray();
        return 0;
    }
    switch (msg) {
        case WM_ERASEBKGND: return 1;
        case WM_PAINT: Paint(); return 0;
        case WM_SIZE:
            if (wp != SIZE_MINIMIZED) {
                PlaceEdit();
                ClampScroll(Layout());
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        case WM_GETMINMAXINFO: {
            MINMAXINFO* m = (MINMAXINFO*)lp;
            m->ptMinTrackSize.x = S(400);
            m->ptMinTrackSize.y = S(520);
            return 0;
        }
        case WM_DPICHANGED: {
            g_dpi = HIWORD(wp);
            RECT* r = (RECT*)lp;
            SetWindowPos(hwnd, nullptr, r->left, r->top, r->right - r->left, r->bottom - r->top, SWP_NOZORDER | SWP_NOACTIVATE);
            EnsureFonts();
            PlaceEdit();
            return 0;
        }
        case WM_CTLCOLOREDIT:
            if ((HWND)lp == g_edit) {
                SetTextColor((HDC)wp, RGB(235, 237, 241));
                SetBkColor((HDC)wp, kBgRef);
                return (LRESULT)g_editBrush;
            }
            break;
        case WM_COMMAND:
            if ((HWND)lp == g_edit) {
                if (HIWORD(wp) == EN_CHANGE) Refilter();
                else if (HIWORD(wp) == EN_SETFOCUS || HIWORD(wp) == EN_KILLFOCUS) {
                    g_editFocus = HIWORD(wp) == EN_SETFOCUS;
                    InvalidateRect(hwnd, nullptr, FALSE);
                }
            }
            return 0;
        case WM_SETFOCUS: SetFocus(g_edit); return 0;
        case WM_TIMER:
            if (wp == T_TICK && g_playing) {
                if (g_pi.hProcess && WaitForSingleObject(g_pi.hProcess, 0) == WAIT_OBJECT_0) StopPlaying();
                else if (IsWindowVisible(hwnd) && !IsIconic(hwnd)) InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        case WM_TRAY:
            if (lp == WM_RBUTTONUP || lp == WM_CONTEXTMENU) TrayMenu();
            else if (lp == WM_LBUTTONUP || lp == WM_LBUTTONDBLCLK) ShowMain();
            return 0;
        case WM_SETCURSOR:
            if (LOWORD(lp) == HTCLIENT) {
                POINT p;
                GetCursorPos(&p);
                ScreenToClient(hwnd, &p);
                bool hand = HitBtn(p.x, p.y) != BTN_NONE || RowAt(p.x, p.y) >= 0;
                SetCursor(LoadCursorW(nullptr, hand ? IDC_HAND : IDC_ARROW));
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
            if (g_dragThumb) {
                Lay l = Layout();
                int ch = ContentH(), vh = ViewH(l);
                float th = std::max(Sf(30), (float)vh * vh / ch);
                float ty = (float)(y - g_dragOffset - l.list.top);
                float frac = ty / std::max(1.0f, vh - th);
                g_scroll = (int)(frac * (ch - vh));
                ClampScroll(l);
                InvalidateRect(hwnd, nullptr, FALSE);
                return 0;
            }
            int hb = HitBtn(x, y), hr = RowAt(x, y);
            if (hb != g_hotBtn || hr != g_hoverRow) {
                g_hotBtn = hb;
                g_hoverRow = hr;
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        }
        case WM_MOUSELEAVE:
            g_trackLeave = false;
            g_hotBtn = BTN_NONE;
            g_hoverRow = -1;
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        case WM_LBUTTONDOWN:
        case WM_LBUTTONDBLCLK: {
            int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
            int b = HitBtn(x, y);
            if (b != BTN_NONE) {
                g_pressBtn = b;
                SetCapture(hwnd);
                InvalidateRect(hwnd, nullptr, FALSE);
                return 0;
            }
            RECT tr;
            POINT p = {x, y};
            if (ThumbRect(tr) && PtInRect(&tr, p)) {
                g_dragThumb = true;
                g_dragOffset = y - tr.top;
                SetCapture(hwnd);
                return 0;
            }
            int row = RowAt(x, y);
            if (row >= 0) {
                g_selGame = g_view[row];
                InvalidateRect(hwnd, nullptr, FALSE);
                if (msg == WM_LBUTTONDBLCLK) PlaySelected();
            }
            SetFocus(g_edit);
            return 0;
        }
        case WM_LBUTTONUP: {
            int x = GET_X_LPARAM(lp), y = GET_Y_LPARAM(lp);
            if (g_dragThumb) {
                g_dragThumb = false;
                ReleaseCapture();
                InvalidateRect(hwnd, nullptr, FALSE);
            } else if (g_pressBtn != BTN_NONE) {
                int pb = g_pressBtn;
                g_pressBtn = BTN_NONE;
                ReleaseCapture();
                if (HitBtn(x, y) == pb) {
                    if (pb == BTN_PLAY && g_selGame >= 0) PlaySelected();
                    else if (pb == BTN_STOP && g_playing) StopPlaying();
                }
                InvalidateRect(hwnd, nullptr, FALSE);
            }
            return 0;
        }
        case WM_MOUSEWHEEL: {
            int d = GET_WHEEL_DELTA_WPARAM(wp);
            Lay l = Layout();
            g_scroll -= (int)((double)d / 120.0 * RowH() * 2);
            ClampScroll(l);
            POINT p = {GET_X_LPARAM(lp), GET_Y_LPARAM(lp)};
            ScreenToClient(hwnd, &p);
            g_hoverRow = RowAt(p.x, p.y);
            InvalidateRect(hwnd, nullptr, FALSE);
            return 0;
        }
        case WM_CLOSE:
            if (g_exiting) DestroyWindow(hwnd);
            else HideToTray();
            return 0;
        case WM_DESTROY:
            StopPlaying();
            RemoveTray();
            PostQuitMessage(0);
            return 0;
    }
    return DefWindowProcW(hwnd, msg, wp, lp);
}

// ---------------------------------------------------------------- stub mode
static std::wstring g_stubText;
static HFONT g_stubFont = nullptr;
static HBRUSH g_stubBrush = nullptr;

static LRESULT CALLBACK StubProc(HWND h, UINT m, WPARAM w, LPARAM l) {
    switch (m) {
        case WM_PAINT: {
            PAINTSTRUCT ps;
            HDC dc = BeginPaint(h, &ps);
            RECT rc;
            GetClientRect(h, &rc);
            FillRect(dc, &rc, g_stubBrush);
            SetBkMode(dc, TRANSPARENT);
            SetTextColor(dc, RGB(235, 237, 241));
            HGDIOBJ old = SelectObject(dc, g_stubFont);
            InflateRect(&rc, -S(14), -S(12));
            DrawTextW(dc, g_stubText.c_str(), -1, &rc, DT_WORDBREAK | DT_NOPREFIX);
            SelectObject(dc, old);
            EndPaint(h, &ps);
            return 0;
        }
        case WM_ERASEBKGND: return 1;
        case WM_CLOSE: DestroyWindow(h); return 0;  // closing the window ends the "game"
        case WM_DESTROY: PostQuitMessage(0); return 0;
    }
    return DefWindowProcW(h, m, w, l);
}

// The stub is a small but real, visible window: the official Discord client is stricter than
// modified clients and may ignore processes that have no normal window.
static int RunStub(DWORD parentPid, const wchar_t* self, bool minimized) {
    HANDLE hp = OpenProcess(SYNCHRONIZE, FALSE, parentPid);
    if (!hp) return 0;
    g_dpi = QueryDpi(nullptr);
    g_stubBrush = CreateSolidBrush(RGB(28, 30, 35));
    g_stubFont = CreateFontW(-S(15), 0, 0, 0, FW_NORMAL, FALSE, FALSE, FALSE, DEFAULT_CHARSET, OUT_DEFAULT_PRECIS, CLIP_DEFAULT_PRECIS,
                             CLEARTYPE_QUALITY, DEFAULT_PITCH | FF_DONTCARE, L"Segoe UI");
    const wchar_t* title = PathFindFileNameW(self);
    g_stubText = std::wstring(L"zatchi is running as  ") + title + L"\r\n\r\nDiscord sees this process as a game. Close this window (or press Stop in zatchi) to end it.";
    WNDCLASSEXW wc = {sizeof(wc)};
    wc.lpfnWndProc = StubProc;
    wc.hInstance = GetModuleHandleW(nullptr);
    wc.hIcon = LoadIconW(wc.hInstance, MAKEINTRESOURCEW(1));
    wc.hIconSm = wc.hIcon;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.hbrBackground = g_stubBrush;
    wc.lpszClassName = kStubClass;
    RegisterClassExW(&wc);
    DWORD style = WS_OVERLAPPED | WS_CAPTION | WS_SYSMENU | WS_MINIMIZEBOX;
    RECT r = {0, 0, S(380), S(120)};
    AdjustWindowRect(&r, style, FALSE);
    int ww = r.right - r.left, wh = r.bottom - r.top;
    RECT wa;
    SystemParametersInfoW(SPI_GETWORKAREA, 0, &wa, 0);
    HWND w = CreateWindowExW(0, kStubClass, title, style, wa.right - ww - S(16), wa.bottom - wh - S(16), ww, wh, nullptr, nullptr, wc.hInstance,
                             nullptr);
    if (w) {
        DarkTitleBar(w);
        ShowWindow(w, minimized ? SW_SHOWMINNOACTIVE : SW_SHOWNOACTIVATE);
        UpdateWindow(w);
    }
    for (;;) {
        DWORD r = MsgWaitForMultipleObjects(1, &hp, FALSE, INFINITE, QS_ALLINPUT);
        if (r == WAIT_OBJECT_0 || r == WAIT_FAILED) break;
        MSG m;
        while (PeekMessageW(&m, nullptr, 0, 0, PM_REMOVE)) {
            if (m.message == WM_QUIT) return 0;
            TranslateMessage(&m);
            DispatchMessageW(&m);
        }
    }
    CloseHandle(hp);
    return 0;
}

// ---------------------------------------------------------------- main
int WINAPI WinMain(HINSTANCE hInst, HINSTANCE, LPSTR, int) {
    g_inst = hInst;
    int argc = 0;
    LPWSTR* argv = CommandLineToArgvW(GetCommandLineW(), &argc);
    bool background = false;
    for (int i = 1; argv && i < argc; i++) {
        if (lstrcmpiW(argv[i], L"--stub") == 0 && i + 1 < argc) {
            bool minimized = false;
            for (int k = i + 2; k < argc; k++)
                if (lstrcmpiW(argv[k], L"--min") == 0) minimized = true;
            int rc = RunStub((DWORD)_wtoi(argv[i + 1]), argv[0], minimized);
            LocalFree(argv);
            return rc;
        }
        if (lstrcmpiW(argv[i], L"--background") == 0) background = true;
    }
    if (argv) LocalFree(argv);

    g_msgShow = RegisterWindowMessageW(L"zatchi_show_main");
    g_msgTaskbar = RegisterWindowMessageW(L"TaskbarCreated");
    HANDLE mtx = CreateMutexW(nullptr, TRUE, kMutexName);
    if (GetLastError() == ERROR_ALREADY_EXISTS) {
        if (!background) PostMessageW(HWND_BROADCAST, g_msgShow, 0, 0);
        return 0;
    }

    GdiplusStartupInput gsi;
    GdiplusStartup(&g_gdip, &gsi, nullptr);
    LoadGames();
    DeleteTree(RunRoot());  // leftovers of a previous crash
    g_editBrush = CreateSolidBrush(kBgRef);

    WNDCLASSEXW wc = {sizeof(wc)};
    wc.style = CS_DBLCLKS;
    wc.lpfnWndProc = WndProc;
    wc.hInstance = hInst;
    wc.hIcon = LoadIconW(hInst, MAKEINTRESOURCEW(1));
    wc.hIconSm = wc.hIcon;
    wc.hCursor = LoadCursorW(nullptr, IDC_ARROW);
    wc.lpszClassName = kMainClass;
    RegisterClassExW(&wc);

    g_dpi = QueryDpi(nullptr);
    g_hwnd = CreateWindowExW(0, kMainClass, L"zatchi", WS_OVERLAPPEDWINDOW & ~WS_MAXIMIZEBOX, CW_USEDEFAULT, CW_USEDEFAULT, S(480), S(720), nullptr,
                             nullptr, hInst, nullptr);
    if (!g_hwnd) return 1;
    g_dpi = QueryDpi(g_hwnd);
    g_edit = CreateWindowExW(0, L"EDIT", L"", WS_CHILD | WS_VISIBLE | ES_AUTOHSCROLL, 0, 0, 100, 20, g_hwnd, (HMENU)100, hInst, nullptr);
    InitCommonControls();
    g_editProc = (WNDPROC)SetWindowLongPtrW(g_edit, GWLP_WNDPROC, (LONG_PTR)EditProc);
    SendMessageW(g_edit, 0x1501 /*EM_SETCUEBANNER*/, TRUE, (LPARAM)L"Search games...");
    SendMessageW(g_edit, EM_SETLIMITTEXT, 100, 0);
    EnsureFonts();
    DarkTitleBar(g_hwnd);

    // restore the last selected game
    std::wstring last = RegGetStr(L"Software\\zatchi", L"Last");
    Refilter();
    if (!last.empty())
        for (size_t i = 0; i < g_games.size(); i++)
            if (g_games[i].name == last) {
                g_selGame = (int)i;
                int row = RowOfGame(g_selGame);
                if (row >= 0) EnsureVisible(row);
                break;
            }
    PlaceEdit();
    AddTray();
    if (!background) {
        ShowWindow(g_hwnd, SW_SHOW);
        UpdateWindow(g_hwnd);
        SetFocus(g_edit);
    }

    MSG m;
    while (GetMessageW(&m, nullptr, 0, 0) > 0) {
        TranslateMessage(&m);
        DispatchMessageW(&m);
    }
    DeleteTree(RunRoot());
    GdiplusShutdown(g_gdip);
    if (mtx) CloseHandle(mtx);
    return (int)m.wParam;
}
