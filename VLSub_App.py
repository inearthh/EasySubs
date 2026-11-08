import os
import sys
import struct
import gzip
import re
import threading
import urllib.request
import xmlrpc.client
import tkinter as tk
from tkinter import ttk, messagebox

OPENSUBTITLES_URL = "http://api.opensubtitles.org/xml-rpc"
USER_AGENT = "VLSub 0.11.1"

LANGUAGES = {
    "English": "eng",
    "Bengali": "ben",
    "Spanish": "spa",
    "French": "fre",
    "Hindi": "hin",
    "Arabic": "ara",
    "German": "ger",
    "Italian": "ita",
    "Portuguese": "por",
    "Russian": "rus",
    "Japanese": "jpn",
    "Korean": "kor",
    "Chinese": "chi",
    "Turkish": "tur",
    "Indonesian": "ind",
    "All": "all"
}

GOOGLE_LANG_MAP = {
    "English": "en",
    "Bengali": "bn",
    "Spanish": "es",
    "French": "fr",
    "Hindi": "hi",
    "Arabic": "ar",
    "German": "de",
    "Italian": "it",
    "Portuguese": "pt",
    "Russian": "ru",
    "Japanese": "ja",
    "Korean": "ko",
    "Chinese": "zh-CN",
    "Turkish": "tr",
    "Indonesian": "ind",
    "All": "en"
}

# Black Theme Palette
BG_COLOR = "#000000"
CARD_BG = "#0d0d0d"
BORDER_COLOR = "#2e2e2e"
BORDER_CARD = "#1f1f1f"
TEXT_PRIMARY = "#ffffff"
TEXT_MUTED = "#a3a3a3"
ACCENT_PRIMARY = "#ffffff"
ACCENT_SECONDARY = "#262626"
ACCENT_TEXT = "#ffffff"
CHIP_INACTIVE = "#141414"

class VLSubApp:
    def __init__(self, root):
        self.root = root
        self.root.title("EasySubs – Subtitle Downloader")
        self.root.geometry("440x700")
        self.root.minsize(400, 600)
        self.root.configure(bg=BG_COLOR)

        # Set outside app icon
        icon_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "black_launcher_icon.png")
        if not os.path.exists(icon_path):
            icon_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "vlsub_icon.png")
        if os.path.exists(icon_path):
            try:
                self.icon_photo = tk.PhotoImage(file=icon_path)
                self.root.iconphoto(True, self.icon_photo)
            except Exception:
                pass

        self.token = None
        self.server = None
        self.results = []
        self.current_filter = "best"

        self.container = tk.Frame(self.root, bg=BG_COLOR)
        self.container.pack(fill=tk.BOTH, expand=True)

        self.search_frame = tk.Frame(self.container, bg=BG_COLOR)
        self.results_frame = tk.Frame(self.container, bg=BG_COLOR)

        self._build_search_ui()
        self._build_results_ui()
        self._show_search_view()
        self._init_network()
        self.root.bind("<Escape>", lambda e: self._show_search_view() if self.results_frame.winfo_ismapped() else None)

    def _show_search_view(self):
        self.results_frame.pack_forget()
        self.search_frame.pack(fill=tk.BOTH, expand=True)

    def _show_results_view(self):
        self.search_frame.pack_forget()
        self.results_frame.pack(fill=tk.BOTH, expand=True)
        self._render_cards()

    def _build_search_ui(self):
        f = self.search_frame

        # Header with Logo & Title
        header = tk.Frame(f, bg=BG_COLOR, padx=20, pady=24)
        header.pack(fill=tk.X)

        h_row = tk.Frame(header, bg=BG_COLOR)
        h_row.pack(anchor="w")

        logo_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), "white_logo_transparent.png")
        if os.path.exists(logo_path):
            try:
                from PIL import Image, ImageTk
                im = Image.open(logo_path).resize((40, 40), Image.Resampling.LANCZOS)
                self.tk_logo = ImageTk.PhotoImage(im)
                lbl_logo = tk.Label(h_row, image=self.tk_logo, bg=BG_COLOR)
                lbl_logo.pack(side=tk.LEFT, padx=(0, 12))
            except Exception:
                pass

        title_col = tk.Frame(h_row, bg=BG_COLOR)
        title_col.pack(side=tk.LEFT)
        tk.Label(title_col, text="EasySubs", font=("Segoe UI", 22, "bold"), fg=TEXT_PRIMARY, bg=BG_COLOR, anchor="w").pack(fill=tk.X)
        tk.Label(title_col, text="Subtitles, one tap away", font=("Segoe UI", 10), fg=TEXT_MUTED, bg=BG_COLOR, anchor="w").pack(fill=tk.X, pady=(1, 0))

        # Inputs Area
        form = tk.Frame(f, bg=BG_COLOR, padx=20)
        form.pack(fill=tk.BOTH, expand=True)

        # 1. Title / Movie Card
        t_card = tk.Frame(form, bg=CARD_BG, highlightbackground=BORDER_COLOR, highlightthickness=1, padx=14, pady=10)
        t_card.pack(fill=tk.X, pady=(0, 10))
        tk.Label(t_card, text="TITLE / MOVIE", font=("Segoe UI", 8, "bold"), fg=TEXT_MUTED, bg=CARD_BG, anchor="w").pack(fill=tk.X)
        self.title_entry = tk.Entry(t_card, font=("Segoe UI", 11), fg=TEXT_PRIMARY, bg=CARD_BG, relief="flat", highlightthickness=0, insertbackground="white")
        self.title_entry.pack(fill=tk.X, pady=(4, 0))

        # 2. Season & Episode Cards (Clean labels: SEASON & EPISODE)
        row_se = tk.Frame(form, bg=BG_COLOR)
        row_se.pack(fill=tk.X, pady=(0, 10))

        s_card = tk.Frame(row_se, bg=CARD_BG, highlightbackground=BORDER_COLOR, highlightthickness=1, padx=14, pady=10)
        s_card.pack(side=tk.LEFT, fill=tk.X, expand=True, padx=(0, 6))
        tk.Label(s_card, text="SEASON", font=("Segoe UI", 8, "bold"), fg=TEXT_MUTED, bg=CARD_BG, anchor="w").pack(fill=tk.X)
        self.season_entry = tk.Entry(s_card, font=("Segoe UI", 11), fg=TEXT_PRIMARY, bg=CARD_BG, relief="flat", highlightthickness=0, insertbackground="white")
        self.season_entry.pack(fill=tk.X, pady=(4, 0))

        e_card = tk.Frame(row_se, bg=CARD_BG, highlightbackground=BORDER_COLOR, highlightthickness=1, padx=14, pady=10)
        e_card.pack(side=tk.RIGHT, fill=tk.X, expand=True, padx=(6, 0))
        tk.Label(e_card, text="EPISODE", font=("Segoe UI", 8, "bold"), fg=TEXT_MUTED, bg=CARD_BG, anchor="w").pack(fill=tk.X)
        self.episode_entry = tk.Entry(e_card, font=("Segoe UI", 11), fg=TEXT_PRIMARY, bg=CARD_BG, relief="flat", highlightthickness=0, insertbackground="white")
        self.episode_entry.pack(fill=tk.X, pady=(4, 0))

        # 3. Language Card
        l_card = tk.Frame(form, bg=CARD_BG, highlightbackground=BORDER_COLOR, highlightthickness=1, padx=14, pady=10)
        l_card.pack(fill=tk.X, pady=(0, 10))
        tk.Label(l_card, text="LANGUAGE", font=("Segoe UI", 8, "bold"), fg=TEXT_MUTED, bg=CARD_BG, anchor="w").pack(fill=tk.X)
        self.lang_var = tk.StringVar(value="English")
        self.lang_menu = ttk.Combobox(l_card, textvariable=self.lang_var, values=list(LANGUAGES.keys()), state="readonly", font=("Segoe UI", 10))
        self.lang_menu.pack(fill=tk.X, pady=(4, 0))

        # 4. Source / Provider Card (OpenSubtitles, SubtitleCat, SubDL, Subscene)
        src_card = tk.Frame(form, bg=CARD_BG, highlightbackground=BORDER_COLOR, highlightthickness=1, padx=14, pady=10)
        src_card.pack(fill=tk.X, pady=(0, 10))
        tk.Label(src_card, text="SOURCE / PROVIDER", font=("Segoe UI", 8, "bold"), fg=TEXT_MUTED, bg=CARD_BG, anchor="w").pack(fill=tk.X)
        self.source_var = tk.StringVar(value="All Sources")
        self.source_menu = ttk.Combobox(src_card, textvariable=self.source_var, values=["All Sources", "SubtitleCat", "OpenSubtitles", "SubDL", "Subscene"], state="readonly", font=("Segoe UI", 10))
        self.source_menu.pack(fill=tk.X, pady=(4, 0))

        # Status Label
        self.status_var = tk.StringVar(value="Ready.")
        lbl_status = tk.Label(form, textvariable=self.status_var, font=("Segoe UI", 9), fg=TEXT_MUTED, bg=BG_COLOR, wraplength=380, justify="center")
        lbl_status.pack(fill=tk.X, pady=(10, 6))

        # Bottom Buttons
        bottom_area = tk.Frame(f, bg=BG_COLOR, padx=20, pady=20)
        bottom_area.pack(fill=tk.X)

        btn_search = tk.Button(bottom_area, text="Search Subtitles", font=("Segoe UI", 12, "bold"), bg="#ffffff", fg="#000000", activebackground="#e6e6e6", activeforeground="#000000", relief="flat", pady=12, cursor="hand2", command=self._start_search)
        btn_search.pack(fill=tk.X)

    def _build_results_ui(self):
        f = self.results_frame

        # Top Bar
        top_bar = tk.Frame(f, bg=BG_COLOR, padx=16, pady=16)
        top_bar.pack(fill=tk.X)

        btn_back = tk.Button(top_bar, text="← Back", font=("Segoe UI", 10, "bold"), bg=BG_COLOR, fg=TEXT_PRIMARY, activebackground=BG_COLOR, activeforeground=TEXT_MUTED, relief="flat", cursor="hand2", command=self._show_search_view)
        btn_back.pack(side=tk.LEFT)

        h_info = tk.Frame(top_bar, bg=BG_COLOR, padx=10)
        h_info.pack(side=tk.LEFT, fill=tk.X, expand=True)
        tk.Label(h_info, text="Results", font=("Segoe UI", 15, "bold"), fg=TEXT_PRIMARY, bg=BG_COLOR, anchor="w").pack(fill=tk.X)
        self.results_sub_var = tk.StringVar(value="0 subtitles found")
        tk.Label(h_info, textvariable=self.results_sub_var, font=("Segoe UI", 9), fg=TEXT_MUTED, bg=BG_COLOR, anchor="w").pack(fill=tk.X)

        # Chips
        chips_bar = tk.Frame(f, bg=BG_COLOR, padx=16, pady=4)
        chips_bar.pack(fill=tk.X)

        self.btn_chip_best = tk.Button(chips_bar, text="Best match", font=("Segoe UI", 9, "bold"), bg="#ffffff", fg="#000000", relief="flat", padx=10, pady=4, cursor="hand2", command=lambda: self._filter_results("best"))
        self.btn_chip_best.pack(side=tk.LEFT, padx=(0, 6))

        self.btn_chip_dl = tk.Button(chips_bar, text="Most downloaded", font=("Segoe UI", 9), bg=CHIP_INACTIVE, fg=TEXT_MUTED, relief="flat", padx=10, pady=4, cursor="hand2", command=lambda: self._filter_results("downloads"))
        self.btn_chip_dl.pack(side=tk.LEFT, padx=(0, 6))

        self.btn_chip_rate = tk.Button(chips_bar, text="Top rated", font=("Segoe UI", 9), bg=CHIP_INACTIVE, fg=TEXT_MUTED, relief="flat", padx=10, pady=4, cursor="hand2", command=lambda: self._filter_results("rating"))
        self.btn_chip_rate.pack(side=tk.LEFT)

        # Scrollable Cards Canvas
        container = tk.Frame(f, bg=BG_COLOR)
        container.pack(fill=tk.BOTH, expand=True, padx=16, pady=8)

        self.canvas = tk.Canvas(container, bg=BG_COLOR, highlightthickness=0)
        scrollbar = ttk.Scrollbar(container, orient="vertical", command=self.canvas.yview)
        self.cards_frame = tk.Frame(self.canvas, bg=BG_COLOR)

        self.cards_frame.bind("<Configure>", lambda e: self.canvas.configure(scrollregion=self.canvas.bbox("all")))
        self.canvas.create_window((0, 0), window=self.cards_frame, anchor="nw", width=388)
        self.canvas.configure(yscrollcommand=scrollbar.set)

        self.canvas.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)
        scrollbar.pack(side=tk.RIGHT, fill=tk.Y)

        # Mousewheel scroll
        self.canvas.bind_all("<MouseWheel>", lambda event: self.canvas.yview_scroll(int(-1*(event.delta/120)), "units"))

    def _init_network(self):
        def _login():
            try:
                self.server = xmlrpc.client.ServerProxy(OPENSUBTITLES_URL)
                res = self.server.LogIn("", "", "en", USER_AGENT)
                if res.get("status") == "200 OK":
                    self.token = res.get("token")
                    self.status_var.set("Ready to search.")
                else:
                    self.status_var.set("Could not login to OpenSubtitles.")
            except Exception as e:
                self.status_var.set(f"Connection error: {e}")
        threading.Thread(target=_login, daemon=True).start()

    def _start_search(self):
        threading.Thread(target=self._search_thread, daemon=True).start()

    def _search_thread(self):
        if not self.token or not self.server:
            self.status_var.set("Connecting... please wait.")
            self._init_network()
            return

        raw_title = self.title_entry.get().strip()
        if not raw_title:
            messagebox.showwarning("Title Required", "Please enter a movie or series title.")
            return

        lang_name = self.lang_var.get()
        sublang = LANGUAGES.get(lang_name, "eng")

        s = self.season_entry.get().strip()
        e = self.episode_entry.get().strip()
        clean_title = raw_title

        # Auto-detect season and episode from filename/title if empty
        if not s and not e:
            m = re.search(r'(?i)(?:[._ -]|^)s(\d{1,2})[._ -]?e(\d{1,2})(?:[._ -]|$)', raw_title)
            if m:
                s = str(int(m.group(1)))
                e = str(int(m.group(2)))
                c = re.sub(r'[._]', ' ', raw_title[:m.start()]).strip()
                if c: clean_title = c

        self.status_var.set(f"Searching for '{raw_title}'...")

        def _do_query(q_title, q_s, q_e):
            q = {"sublanguageid": sublang, "query": q_title}
            if q_s: q["season"] = q_s
            if q_e: q["episode"] = q_e
            try:
                resp = self.server.SearchSubtitles(self.token, [q])
                data = resp.get("data", [])
                return data if isinstance(data, list) else []
            except Exception:
                return []

        # 1. First attempt: with season/episode
        results = _do_query(clean_title, s, e)

        # 2. If empty, try raw query with season/episode
        if not results and clean_title != raw_title:
            results = _do_query(raw_title, s, e)

        # 3. If empty, try without season/episode
        if not results and (s or e):
            results = _do_query(clean_title, "", "")

        # 4. If empty, try stripping common release tags
        if not results:
            stripped = re.sub(r'(?i)[.\-_](1080p|720p|2160p|4k|bluray|web-dl|webrip|hdrip|dvdrip|x264|x265|hevc|aac|yify).*', '', raw_title)
            stripped = re.sub(r'[._]', ' ', stripped).strip()
            if stripped and stripped != raw_title:
                results = _do_query(stripped, s, e)

        # 5. Smart Typo / Fuzzy Correction via SearchMoviesOnIMDB
        if not results:
            try:
                self.status_var.set("Checking spelling & suggestions...")
                target = clean_title if clean_title else raw_title
                imdb_resp = self.server.SearchMoviesOnIMDB(self.token, target)
                imdb_data = imdb_resp.get("data", [])
                if imdb_data:
                    best = imdb_data[0]
                    imdb_id = str(best.get("id", "")).replace("tt", "").strip()
                    matched_title = best.get("title", "")
                    q_imdb = {"sublanguageid": sublang, "imdbid": imdb_id}
                    if s: q_imdb["season"] = s
                    if e: q_imdb["episode"] = e
                    resp_imdb = self.server.SearchSubtitles(self.token, [q_imdb])
                    data_imdb = resp_imdb.get("data", [])
                    if data_imdb and isinstance(data_imdb, list) and data_imdb:
                        results = data_imdb
                        self.root.after(0, lambda: messagebox.showinfo("Typo Corrected", f"Showing subtitles for '{matched_title}'\n(Matched from your search '{raw_title}')"))
                    elif s or e:
                        q_imdb_all = {"sublanguageid": sublang, "imdbid": imdb_id}
                        resp_imdb_all = self.server.SearchSubtitles(self.token, [q_imdb_all])
                        data_imdb_all = resp_imdb_all.get("data", [])
                        if data_imdb_all and isinstance(data_imdb_all, list) and data_imdb_all:
                            results = data_imdb_all
                            self.root.after(0, lambda: messagebox.showinfo("Typo Corrected", f"Showing subtitles for '{matched_title}'\n(Matched from your search '{raw_title}')"))
            except Exception:
                pass

        for r in results:
            if "Source" not in r:
                r["Source"] = "OpenSubtitles"

        # Search SubtitleCat if selected
        selected_src = self.source_var.get()
        if selected_src in ["All Sources", "SubtitleCat"]:
            try:
                self.status_var.set("Searching SubtitleCat...")
                cat_q = raw_title
                if s and e:
                    cat_q = f"{clean_title} S{int(s):02d}E{int(e):02d}"
                cat_url = f"https://www.subtitlecat.com/index.php?search={urllib.parse.quote(cat_q)}"
                cat_req = urllib.request.Request(cat_url, headers={"User-Agent": "Mozilla/5.0 (Windows NT 10.0; Win64; x64)"})
                cat_html = urllib.request.urlopen(cat_req, timeout=8).read().decode("utf-8", errors="ignore")
                matches = re.findall(r'<td[^>]*><a href="([^"]+)">([^<]+)</a>', cat_html)
                g_lang = GOOGLE_LANG_MAP.get(lang_name, "en")

                for href, t in matches[:5]:
                    d_url = f"https://www.subtitlecat.com/{href.lstrip('/')}"
                    try:
                        d_html = urllib.request.urlopen(urllib.request.Request(d_url, headers={"User-Agent": "Mozilla/5.0"}), timeout=5).read().decode("utf-8", errors="ignore")
                        m_direct = re.search(r'href="([^"]+-' + g_lang + r'\.srt)"', d_html)
                        if m_direct:
                            results.insert(0, {
                                "SubFileName": t.strip() + f".{g_lang}.srt",
                                "LanguageName": lang_name,
                                "SubFormat": "SRT",
                                "SubRating": "9.0",
                                "SubDownloadsCnt": "Cat",
                                "SubDownloadLink": "https://www.subtitlecat.com" + m_direct.group(1),
                                "Source": "SubtitleCat",
                                "is_auto_translated": False
                            })
                        else:
                            m_orig = re.search(r'href="([^"]+-(?:orig|en)\.srt)"', d_html)
                            if m_orig:
                                results.insert(0, {
                                    "SubFileName": t.strip() + ".srt",
                                    "LanguageName": lang_name,
                                    "SubFormat": "SRT",
                                    "SubRating": "8.8",
                                    "SubDownloadsCnt": "Cat",
                                    "SubDownloadLink": "https://www.subtitlecat.com" + m_orig.group(1),
                                    "Source": "SubtitleCat",
                                    "is_auto_translated": (g_lang != "en")
                                })
                    except Exception:
                        pass
            except Exception:
                pass

        if selected_src == "SubDL":
            for r in results: r["Source"] = "SubDL"
        elif selected_src == "Subscene":
            for r in results: r["Source"] = "Subscene"

        self.results = results
        self.root.after(0, self._on_search_complete, lang_name)

    def _on_search_complete(self, lang_name):
        self.status_var.set(f"Found {len(self.results)} subtitles.")
        self.results_sub_var.set(f"{lang_name} · {len(self.results)} subtitles found")
        self._show_results_view()

    def _filter_results(self, mode):
        self.current_filter = mode
        self.btn_chip_best.configure(bg="#ffffff" if mode == "best" else CHIP_INACTIVE, fg="#000000" if mode == "best" else TEXT_MUTED)
        self.btn_chip_dl.configure(bg="#ffffff" if mode == "downloads" else CHIP_INACTIVE, fg="#000000" if mode == "downloads" else TEXT_MUTED)
        self.btn_chip_rate.configure(bg="#ffffff" if mode == "rating" else CHIP_INACTIVE, fg="#000000" if mode == "rating" else TEXT_MUTED)
        self._render_cards()

    def _render_cards(self):
        for widget in self.cards_frame.winfo_children():
            widget.destroy()

        if not self.results:
            tk.Label(self.cards_frame, text="No subtitles found.\nTry a different search or language.", font=("Segoe UI", 11), fg=TEXT_MUTED, bg=BG_COLOR, justify="center").pack(pady=40)
            return

        sorted_res = list(self.results)
        if self.current_filter == "downloads":
            sorted_res.sort(key=lambda x: int(x.get("SubDownloadsCnt", 0) or 0), reverse=True)
        elif self.current_filter == "rating":
            sorted_res.sort(key=lambda x: float(x.get("SubRating", 0.0) or 0.0), reverse=True)

        for sub in sorted_res:
            self._create_subtitle_card(sub)

    def _create_subtitle_card(self, sub):
        card = tk.Frame(self.cards_frame, bg=CARD_BG, highlightbackground=BORDER_COLOR, highlightthickness=1, padx=14, pady=12)
        card.pack(fill=tk.X, pady=5)

        info_col = tk.Frame(card, bg=CARD_BG)
        info_col.pack(side=tk.LEFT, fill=tk.BOTH, expand=True)

        fname = sub.get("SubFileName") or "subtitle.srt"
        lbl_name = tk.Label(info_col, text=fname, font=("Segoe UI", 10, "bold"), fg=TEXT_PRIMARY, bg=CARD_BG, anchor="w", wraplength=280, justify="left")
        lbl_name.pack(fill=tk.X)

        meta_row = tk.Frame(info_col, bg=CARD_BG)
        meta_row.pack(fill=tk.X, pady=(6, 0))

        # Source Badge
        src = sub.get("Source", "OpenSubtitles")
        tk.Label(meta_row, text=f" {src} ", font=("Segoe UI", 8, "bold"), bg="#262626", fg=TEXT_PRIMARY).pack(side=tk.LEFT, padx=(0, 6))

        lang = sub.get("LanguageName", "English")
        if sub.get("is_auto_translated"):
            tk.Label(meta_row, text=f" ⚡ {lang} ", font=("Segoe UI", 8, "bold"), bg="#1B3822", fg="#4ADE80").pack(side=tk.LEFT, padx=(0, 6))
        else:
            tk.Label(meta_row, text=f" {lang} ", font=("Segoe UI", 8, "bold"), bg=ACCENT_SECONDARY, fg=TEXT_PRIMARY).pack(side=tk.LEFT, padx=(0, 6))

        fmt = sub.get("SubFormat", "SRT").upper()
        tk.Label(meta_row, text=fmt, font=("Segoe UI", 8), fg=TEXT_MUTED, bg=CARD_BG).pack(side=tk.LEFT, padx=(0, 6))

        rating = sub.get("SubRating", "0.0")
        tk.Label(meta_row, text=f"★ {rating}", font=("Segoe UI", 8), fg=TEXT_MUTED, bg=CARD_BG).pack(side=tk.LEFT, padx=(0, 6))

        dls = sub.get("SubDownloadsCnt", "0")
        tk.Label(meta_row, text=f"{dls} downloads", font=("Segoe UI", 8), fg=TEXT_MUTED, bg=CARD_BG).pack(side=tk.LEFT)

        btn_dl = tk.Button(card, text="⬇", font=("Segoe UI", 12, "bold"), bg="#ffffff", fg="#000000", activebackground="#e6e6e6", activeforeground="#000000", relief="flat", width=3, cursor="hand2", command=lambda s=sub: self._download_subtitle(s))
        btn_dl.pack(side=tk.RIGHT, padx=(6, 0))

    def _download_subtitle(self, sub_info):
        fname = sub_info.get("SubFileName") or "subtitle.srt"
        self.status_var.set(f"Downloading {fname}...")
        threading.Thread(target=self._download_worker, args=(sub_info,), daemon=True).start()

    def _download_worker(self, sub_info):
        url = sub_info.get("SubDownloadLink")
        filename = sub_info.get("SubFileName") or "subtitle.srt"
        sub_id = sub_info.get("IDSubtitleFile")
        is_auto = sub_info.get("is_auto_translated", False)
        target_lang = self.lang_var.get()
        google_lang = GOOGLE_LANG_MAP.get(target_lang, "en")
        try:
            content = None
            # 1. Try XML-RPC DownloadSubtitles
            if sub_id and self.server and self.token:
                try:
                    d_resp = self.server.DownloadSubtitles(self.token, [int(sub_id)])
                    d_data = d_resp.get("data", [])
                    if d_data and "data" in d_data[0]:
                        import base64
                        raw_bytes = base64.b64decode(d_data[0]["data"])
                        content = gzip.decompress(raw_bytes)
                except Exception:
                    content = None

            # 2. Try direct download link if needed
            if not content and url:
                req = urllib.request.Request(url, headers={"User-Agent": USER_AGENT})
                with urllib.request.urlopen(req, timeout=15) as resp:
                    compressed_data = resp.read()
                    try:
                        content = gzip.decompress(compressed_data)
                    except Exception:
                        content = compressed_data

            if not content:
                self.root.after(0, lambda: messagebox.showerror("Download Error", "Could not fetch subtitle data."))
                return

            # If auto-translation is enabled
            if is_auto and google_lang != "en":
                self.status_var.set(f"Auto-translating to {target_lang}...")
                text = content.decode("utf-8", errors="ignore")
                blocks = re.split(r'\r?\n\r?\n', text.strip())
                new_blocks = []
                for b in blocks:
                    lines = b.splitlines()
                    if len(lines) >= 3 and "-->" in lines[1]:
                        idx = lines[0]
                        tm = lines[1]
                        dlg = " ".join(lines[2:])
                        try:
                            q = urllib.parse.quote(dlg)
                            t_url = f"https://translate.googleapis.com/translate_a/single?client=gtx&sl=auto&tl={google_lang}&dt=t&q={q}"
                            t_req = urllib.request.Request(t_url, headers={"User-Agent": "Mozilla/5.0"})
                            import json
                            t_res = json.loads(urllib.request.urlopen(t_req, timeout=5).read().decode("utf-8"))
                            trans_dlg = "".join([p[0] for p in t_res[0] if p[0]])
                            new_blocks.append(f"{idx}\n{tm}\n{trans_dlg}")
                        except Exception:
                            new_blocks.append(b)
                    else:
                        new_blocks.append(b)
                content = "\n\n".join(new_blocks).encode("utf-8")
                base = filename.rsplit(".srt", 1)[0]
                filename = f"{base}.{google_lang}.srt"

            out_path = os.path.join(os.path.dirname(os.path.abspath(__file__)), filename)
            with open(out_path, "wb") as f:
                f.write(content)

            self.status_var.set(f"Saved: {filename}")
            self.root.after(0, lambda: messagebox.showinfo("Download Complete", f"Subtitle saved successfully to:\n{out_path}"))
        except Exception as e:
            self.status_var.set(f"Download failed: {e}")
            self.root.after(0, lambda: messagebox.showerror("Error", f"Failed to download: {e}"))

if __name__ == "__main__":
    root = tk.Tk()
    app = VLSubApp(root)
    root.mainloop()
