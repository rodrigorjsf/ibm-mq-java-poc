#!/usr/bin/env python3
"""
Bilingual build (issue #13): deterministic single-file standalone HTML embedding BOTH the
canonical English guide and its pt-BR i18n source, with an in-page language toggle.

Pipeline:
  1. render_lang() runs the SAME single-language pipeline on each source independently:
       - Read SOURCE markdown; heal hard-wrap artifacts.
       - Convert the BODY with python-markdown (tables, fenced_code, attr_list, toc,
         sane_lists, md_in_html) using a GitHub-replica slugify so the in-document TOC
         anchors (#section-1--... / #seção-1--... with unicode + double-hyphen) resolve.
       - Split <blockquote> at each callout-emoji boundary (✅/❌/⚠️/ℹ️) into callouts;
         tag <pre><code> with a per-language copy button; mark code for the JS highlighter.
  2. build() assembles ONE shell: one <main> with two <section class="lang-pane"> (en/pt)
     and two nav panes. Visibility follows <html data-lang> (set by an early no-FOUC head
     script: stored > browser > pt-BR fallback); a JS toggle swaps panes ([hidden]) + the
     UI chrome (I18N dict) and lazy-renders the revealed pane's Mermaid (hidden panes have
     no layout box, so startOnLoad is off and setLang calls mermaid.run on reveal).
     Self-contained: embedded CSS + JS + inlined Mermaid runtime, NO external network load.
  3. The PARITY GATE (sys.exit(1) on any mismatch):
       - parity_lang(): per-language source<->rendered-fragment parity (run for EN and pt).
       - cross_lang_parity(): EN<->pt-BR STRUCTURAL sync (headings/code/mermaid/callouts/
         tables identical; labels localized). This is the bilingual sync guarantee.
       - standalone_scan(): the final page has ZERO real external resource loads.

Run:
  .venv-docs/bin/python docs/build-html.py              # build + full bilingual parity gate
  .venv-docs/bin/python docs/build-html.py --self-test  # prove the gate fails on a mismatch
"""

import html
import re
import sys
import urllib.request
from pathlib import Path

import markdown
from markdown.extensions.toc import TocExtension

DOCS = Path("/home/rodrigo/IBM-MQ/docs")
# Bilingual sources (issue #13): the EN doc is the canonical guide; the pt-BR doc is the
# i18n source. Both are embedded in ONE standalone page with an in-page language toggle.
GUIDE_EN = DOCS / "guide-ibmmq-jms-micronaut.md"
GUIDE_PT = DOCS / "i18n" / "guia-ibmmq-jms-micronaut.md"
LANGS = ("en", "pt")
OUT = DOCS / "index.html"
# Canonical bibliography (issue #14): the standalone HTML's References section is GENERATED
# from this single file by render_references(), so the two never diverge. It is rendered as a
# language-NEUTRAL <section> OUTSIDE both <section class="lang-pane"> body fragments — never
# appended into en["body"]/pt["body"] and never given the lang-pane class — so it stays
# invisible to all four parity gates (parity_lang, cross_lang_parity, standalone_scan,
# assembled_page_check) while still rendering on the page in either language.
REFERENCES = DOCS / "references.md"

# Per-language chrome rendered INTO each pane's markup (the parts python emits): code-block
# copy button label + its post-click feedback (carried on data-copied for the shared JS) +
# its aria-label. Page-level chrome that the JS swaps on toggle lives in the JS I18N dict.
CHROME = {
    "en": {"copy": "Copy", "copied": "Copied!", "copy_aria": "Copy code to clipboard"},
    "pt": {"copy": "Copiar", "copied": "Copiado!", "copy_aria": "Copiar código para a área de transferência"},
}

# Vendored Mermaid runtime (fetched ONCE at build time, then inlined into the
# standalone HTML so edge animations play fully offline). docs/vendor/ is gitignored.
VENDOR = DOCS / "vendor"
MERMAID_JS = VENDOR / "mermaid.min.js"
# Self-contained classic-script bundle (esbuild IIFE) that ends with
#   globalThis["mermaid"] = ...
# i.e. it exposes a global `mermaid` and carries flowchart/sequence renderers inline
# with ZERO runtime external imports/fetches (audited: 0 import(), no from"http,
# no fetch("http, no CDN host literals). v11.x ships >=11.4 here (>=11.3 needed for
# edge animation). If absent, it is fetched from this URL.
MERMAID_URL = "https://cdn.jsdelivr.net/npm/mermaid@11/dist/mermaid.min.js"

EMOJI = {"✅": "boa-pratica", "❌": "ma-pratica", "⚠️": "atencao", "ℹ️": "nota"}
EMOJI_RE = re.compile(r"^(✅|❌|⚠️|ℹ️)")


# ---------------------------------------------------------------------------
# VENDOR the Mermaid runtime: fetch the self-contained minified bundle ONCE if
# absent. The downloaded file is verified to be a self-contained classic-script
# bundle (global `mermaid`, no remaining external sub-imports) at the audit step
# in CLAUDE notes; here we only guarantee presence + a sane size.
# ---------------------------------------------------------------------------
def ensure_mermaid_runtime():
    if MERMAID_JS.exists() and MERMAID_JS.stat().st_size > 500_000:
        return MERMAID_JS.read_text(encoding="utf-8")
    VENDOR.mkdir(parents=True, exist_ok=True)
    req = urllib.request.Request(MERMAID_URL, headers={"User-Agent": "build-html/1.0"})
    data = urllib.request.urlopen(req, timeout=60).read()
    if len(data) < 500_000:
        raise RuntimeError(
            f"fetched Mermaid bundle is only {len(data)} bytes — looks like a "
            f"chunk-loading stub, not the self-contained ~3MB bundle"
        )
    text = data.decode("utf-8")
    # self-containment guard: a self-contained classic bundle must expose a global
    # `mermaid` and must NOT perform runtime external module loads.
    if 'globalThis["mermaid"]' not in text and "self.mermaid" not in text:
        raise RuntimeError("vendored Mermaid does not expose a global `mermaid`")
    MERMAID_JS.write_bytes(data)
    return text


# ---------------------------------------------------------------------------
# Heal soft-wrap-split bold markers.
# The source is hard-wrapped; the wrap split several `**bold**` openers across a
# line boundary, e.g. "... e *\n*COD** ..." (meant "... e **COD**"). CommonMark
# parses `*\n*` as broken emphasis (stray literal `*` + scrambled <em>). We rejoin
# a line ending in a lone trailing `*` with the next line that starts with `*`,
# OUTSIDE fenced code. Verified to introduce zero false positives against bullets.
# ---------------------------------------------------------------------------
def heal_soft_wraps(src: str):
    """Rejoin a `**bold**` delimiter run that the hard-wrap split across a line break.

    Two seam shapes, both repaired by PLAIN concatenation (preserving the space before
    the trailing marker so the bold does not become intraword):
      type-1  L ends with a lone trailing '*'         + N starts with '*'   (split opener)
      type-2  L has a dangling '**' opener (odd count) + N starts with '**' (stranded closer)
    The next line must NOT be a "* " bullet. Iterate the cleaned stray-'*' gate to 0.
    """

    def emphasis_unbalanced(line: str) -> bool:
        # strip inline-code spans so '**' inside backticks does not count
        stripped = re.sub(r"`[^`]*`", "", line)
        return stripped.count("**") % 2 == 1

    lines = src.split("\n")
    out = []
    in_fence = False
    healed = 0
    i = 0
    n = len(lines)
    while i < n:
        ln = lines[i]
        if ln.startswith("```"):
            in_fence = not in_fence
            out.append(ln)
            i += 1
            continue
        nxt = lines[i + 1] if i + 1 < n else ""
        nxt_l = nxt.lstrip()
        is_bullet = bool(re.match(r"\*\s", nxt_l))
        join = False
        if not in_fence and i + 1 < n and nxt_l.startswith("*") and not is_bullet:
            type1 = bool(re.search(r"(?<!\*)\*\s*$", ln))  # line ends in a lone '*'
            type2 = nxt_l.startswith("**") and emphasis_unbalanced(ln)
            join = type1 or type2
        if join:
            # plain concatenation: L's trailing '*' + N's leading '*' reunite the run;
            # the space before L's marker survives so the bold is not intraword.
            out.append(ln + nxt_l)
            healed += 1
            i += 2
            continue
        out.append(ln)
        i += 1
    return "\n".join(out), healed


# ---------------------------------------------------------------------------
# Heal a list packed directly under a paragraph with no blank separator.
# python-markdown (and the sane_lists extension) will not let a '-'/'1.' list
# interrupt a paragraph without a preceding blank line — the items then render as
# literal "- " text inside the <p>. We insert a separator ONLY where a list STARTS
# after a paragraph (state machine; continuation lines keep us "in_list" so we never
# split a multi-line item). Inside a blockquote the separator must be a line of just
# '>' (an empty line would end the blockquote and eject the list from the callout).
# ---------------------------------------------------------------------------
def _list_item_kind(line: str):
    t = line
    while t[:1] == ">":
        t = t[1:]
    t = t.lstrip()
    if re.match(r"[-+*]\s", t):
        return "ul"
    if re.match(r"\d+\.\s", t):
        return "ol"
    return None


def heal_blockquote_lists(src: str):
    lines = src.split("\n")
    out = []
    in_fence = False
    in_list = False
    prev_blank = True
    inserted = 0
    for ln in lines:
        if ln.startswith("```"):
            in_fence = not in_fence
            in_list = False
            prev_blank = True
            out.append(ln)
            continue
        if in_fence:
            out.append(ln)
            continue
        blank = ln.strip() == "" or ln.strip() == ">"
        kind = _list_item_kind(ln)
        if kind and not in_list and not prev_blank:
            # starting a list right after a paragraph: insert a separator.
            # use '>' inside a blockquote, empty line otherwise.
            out.append(">" if ln.lstrip().startswith(">") or ln.startswith(">") else "")
            inserted += 1
        if kind:
            in_list = True
        elif blank:
            in_list = False
        prev_blank = blank
        out.append(ln)
    return "\n".join(out), inserted


# ---------------------------------------------------------------------------
# GitHub-replica slugify (unicode-preserving; NO whitespace collapse so that a
# dropped em-dash leaves a double hyphen, matching the hardcoded Sumário anchors)
# ---------------------------------------------------------------------------
def gh_slugify(value, separator):
    v = value.strip().lower()
    v = re.sub(r"[^\w\s-]", "", v, flags=re.UNICODE)
    return v.replace(" ", separator)


# ---------------------------------------------------------------------------
# SOURCE-side measurements (computed, never hardcoded)
# ---------------------------------------------------------------------------
def measure_source(src: str):
    lines = src.split("\n")
    in_fence = False
    fence_is_mermaid = False
    headings = 0
    fence_blocks = 0
    mermaid_blocks = 0
    table_seps = 0
    callouts = {t: 0 for t in EMOJI.values()}
    anchors = set()
    # ORDERED structure signature (issue #13 cross-language gate): a document-order token
    # sequence capturing element TYPE, heading LEVEL, callout TYPE, and per-table ROW count.
    # Scalar counts alone miss a reorder, an H2<->H3 re-level (the scalar `headings` lumps
    # h1-h3), a dropped table ROW (table count unchanged), or a swapped block — all of which
    # shift this signature, so EN<->pt divergence is caught structurally, not just by totals.
    structure = []
    table_rows = 0

    def flush_table():
        nonlocal table_rows
        if table_rows:
            structure.append(("table", table_rows))
            table_rows = 0

    for ln in lines:
        if ln.startswith("```"):
            flush_table()
            if not in_fence:
                # opening fence: classify by info string. ```mermaid blocks are
                # DIAGRAMS (diverted to <pre class="mermaid">), NOT code blocks, so
                # they are tallied separately and excluded from code_blocks.
                fence_is_mermaid = ln.strip().startswith("```mermaid")
                if fence_is_mermaid:
                    mermaid_blocks += 1
                    structure.append(("mermaid",))
                else:
                    fence_blocks += 1
                    structure.append(("code",))
            else:
                fence_is_mermaid = False
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        # table block: a contiguous run of pipe-led lines (header + separator + data rows).
        if ln.startswith("|"):
            table_rows += 1
            # GFM table separator row: |---|:--:|...| (must contain a dash)
            if re.match(r"^\|[\s:|-]+\|\s*$", ln) and "-" in ln:
                table_seps += 1
            continue
        flush_table()
        hm = re.match(r"^(#{1,6}) ", ln)
        if hm:
            level = len(hm.group(1))
            if level <= 3:
                headings += 1  # scalar count stays h1-h3 (matches count_output's <h[123]>)
            structure.append(("h", level))
        m = re.match(r"^> (✅|❌|⚠️|ℹ️)", ln)
        if m:
            callouts[EMOJI[m.group(1)]] += 1
            structure.append(("callout", EMOJI[m.group(1)]))
    flush_table()

    # in-document anchors referenced by the Sumário: ](#...)
    for m in re.finditer(r"\]\(#([^)]+)\)", src):
        anchors.add(m.group(1))

    return {
        "headings": headings,
        "tables": table_seps,
        "code_blocks": fence_blocks,
        "mermaid_blocks": mermaid_blocks,
        "callouts": callouts,
        "callouts_total": sum(callouts.values()),
        "anchors": anchors,
        "structure": structure,
    }


# ---------------------------------------------------------------------------
# Blockquote -> callout splitting.
# python-markdown renders a run of "> ..." lines as a single <blockquote> that may
# contain several emoji-led <p>/<ul>/<pre> children (e.g. a ✅ followed by a ❌).
# We split at each emoji boundary: each emoji-led child opens a new callout segment;
# subsequent non-emoji children attach to the current segment. A blockquote with no
# emoji-led child stays a plain <blockquote>.
# ---------------------------------------------------------------------------
def split_callouts(body: str):
    counts = {t: 0 for t in EMOJI.values()}

    def child_blocks(inner: str):
        """Split blockquote inner HTML into top-level child element strings."""
        blocks, depth, buf, i, n = [], 0, [], 0, len(inner)
        tag_re = re.compile(r"</?([a-zA-Z][a-zA-Z0-9]*)\b[^>]*?(/?)>")
        while i < n:
            m = tag_re.match(inner, i)
            if m:
                buf.append(m.group(0))
                is_close = m.group(0).startswith("</")
                self_closing = m.group(2) == "/" or m.group(1).lower() in (
                    "br", "hr", "img", "input", "meta", "link",
                )
                if is_close:
                    depth -= 1
                elif not self_closing:
                    depth += 1
                i = m.end()
                if depth == 0 and is_close:
                    blocks.append("".join(buf))
                    buf = []
            else:
                ch = inner[i]
                if depth == 0 and ch.strip() == "":
                    i += 1
                    continue
                buf.append(ch)
                i += 1
        tail = "".join(buf).strip()
        if tail:
            blocks.append(tail)
        return blocks

    def first_text_emoji(block: str):
        """Leading emoji of the first text in a child block, else None."""
        text = re.sub(r"<[^>]+>", "", block)
        text = html.unescape(text).lstrip()
        m = EMOJI_RE.match(text)
        return m.group(1) if m else None

    def repl(m: re.Match):
        inner = m.group(1)
        blocks = child_blocks(inner)
        if not any(first_text_emoji(b) for b in blocks):
            return m.group(0)  # plain blockquote, leave untouched

        segments = []  # list of (emoji, [blocks])
        current = None
        leading_non_emoji = []
        for b in blocks:
            e = first_text_emoji(b)
            if e:
                current = [e, [b]]
                segments.append(current)
            elif current is not None:
                current[1].append(b)
            else:
                leading_non_emoji.append(b)

        out_parts = []
        # any pre-emoji content (rare/none) is preserved inside a neutral blockquote
        if leading_non_emoji:
            out_parts.append("<blockquote>" + "".join(leading_non_emoji) + "</blockquote>")
        for emoji, segblocks in segments:
            typ = EMOJI[emoji]
            counts[typ] += 1
            # strip the leading emoji glyph (+ following space) from the first block so it
            # does not render twice next to the icon span.
            segblocks = list(segblocks)
            segblocks[0] = re.sub(
                rf"({re.escape(emoji)})\s?", "", segblocks[0], count=1
            )
            inner_html = "".join(segblocks)
            out_parts.append(
                f'<div class="callout callout-{typ}" role="note">'
                f'<span class="callout-icon" aria-hidden="true">{emoji}</span>'
                f'<div class="callout-body">{inner_html}</div>'
                f"</div>"
            )
        return "".join(out_parts)

    new_body = re.sub(r"<blockquote>(.*?)</blockquote>", repl, body, flags=re.DOTALL)
    return new_body, counts


# ---------------------------------------------------------------------------
# Mermaid diagrams: ```mermaid fenced blocks are rendered by python-markdown's
# fenced_code as <pre><code class="language-mermaid">ESCAPED_SRC</code></pre>. We
# DIVERT them to the DIAGRAM path BEFORE decorate_code runs: re-emit each as
# <pre class="mermaid">ESCAPED_SRC</pre> so the client-side Mermaid runtime renders
# them. They get NO "Copiar" button and are NOT run through the syntax highlighter.
# The fenced_code-escaped inner text is exactly what Mermaid wants: its textContent
# (read by mermaid) un-escapes &lt;br/&gt; back to the literal <br/> the diagram needs.
# ---------------------------------------------------------------------------
def divert_mermaid(body: str):
    count = 0

    def repl(m: re.Match):
        nonlocal count
        count += 1
        inner = m.group(1)  # already HTML-escaped by fenced_code
        return f'<pre class="mermaid">{inner}</pre>'

    new_body = re.sub(
        r'<pre><code class="language-mermaid">(.*?)</code></pre>',
        repl,
        body,
        flags=re.DOTALL,
    )
    return new_body, count


# ---------------------------------------------------------------------------
# Code blocks: add a language label + copy button. The actual highlighting runs
# client-side (see JS). We keep the escaped code verbatim in a data attribute is
# unnecessary — the JS reads textContent of the <code> element directly.
# ---------------------------------------------------------------------------
def decorate_code(body: str, chrome):
    copy = html.escape(chrome["copy"])
    copied = html.escape(chrome["copied"], quote=True)
    copy_aria = html.escape(chrome["copy_aria"], quote=True)

    def repl(m: re.Match):
        attrs = m.group(1)
        code = m.group(2)
        lang_m = re.search(r'class="language-([\w-]+)"', attrs)
        lang = lang_m.group(1) if lang_m else "text"
        label = lang
        return (
            '<div class="code-wrap">'
            f'<div class="code-toolbar"><span class="code-lang">{html.escape(label)}</span>'
            f'<button type="button" class="copy-btn" data-copied="{copied}" '
            f'aria-label="{copy_aria}">{copy}</button></div>'
            f'<pre class="code-block" data-lang="{html.escape(lang)}"><code{attrs}>{code}</code></pre>'
            "</div>"
        )

    return re.sub(
        r"<pre><code([^>]*)>(.*?)</code></pre>", repl, body, flags=re.DOTALL
    )


# ---------------------------------------------------------------------------
# Build the navigation tree from rendered H2/H3 (with their generated ids).
# ---------------------------------------------------------------------------
def build_nav(body: str):
    items = []  # (level, id, text)
    for m in re.finditer(
        r'<h([23]) id="([^"]+)">(.*?)</h[23]>', body, flags=re.DOTALL
    ):
        level = int(m.group(1))
        hid = m.group(2)
        text = re.sub(r"<[^>]+>", "", m.group(3))
        text = html.unescape(text).strip()
        items.append((level, hid, text))

    parts = ['<ul class="nav-list">']
    for level, hid, text in items:
        cls = "nav-h2" if level == 2 else "nav-h3"
        parts.append(
            f'<li class="{cls}"><a href="#{html.escape(hid, quote=True)}" '
            f'class="nav-link" data-target="{html.escape(hid, quote=True)}">'
            f"{html.escape(text)}</a></li>"
        )
    parts.append("</ul>")
    return "\n".join(parts)


CSS = r"""
:root{
  --bg:#f7f8fa; --surface:#ffffff; --text:#1f2430; --muted:#5b6472;
  --accent:#2f5d6e; --accent-strong:#234955;
  --border:#dfe3e8; --code-bg:#f3f4f6; --code-text:#1f2430; --code-comment:#5b6472;
  --good-bg:#eef6ef; --good-bd:#3f7d52; --bad-bg:#fbeeee; --bad-bd:#b3403a;
  --warn-bg:#fbf3e3; --warn-bd:#9a6a16; --note-bg:#eaf1f5; --note-bd:#2f5d6e;
  --sidebar-w:300px;
  --kw:#7a3e9d; --str:#2f6f43; --num:#9a5b16; --com:#5b6472; --ann:#2f5d6e; --tag:#7a3e9d; --attr:#9a5b16;
  --shadow:0 1px 2px rgba(31,36,48,.06),0 2px 8px rgba(31,36,48,.06);
}
*{box-sizing:border-box;}
html{scroll-behavior:smooth;}
body{
  margin:0; background:var(--bg); color:var(--text);
  font-family:'Inter',system-ui,-apple-system,'Segoe UI',Roboto,sans-serif;
  font-size:16px; line-height:1.6; -webkit-font-smoothing:antialiased;
}
a{color:var(--accent); text-decoration:none;}
a:hover{text-decoration:underline;}
a:focus-visible,button:focus-visible,input:focus-visible,.nav-link:focus-visible{
  outline:3px solid var(--accent); outline-offset:2px; border-radius:4px;
}
.skip-link{
  position:absolute; left:8px; top:-48px; z-index:1000; background:var(--surface);
  color:var(--text); padding:10px 16px; border:1px solid var(--border); border-radius:6px;
  transition:top .15s ease;
}
.skip-link:focus{top:8px;}

/* ---- layout ---- */
.layout{display:flex; align-items:flex-start;}
.sidebar{
  position:fixed; top:0; left:0; width:var(--sidebar-w); height:100vh;
  background:var(--surface); border-right:1px solid var(--border);
  display:flex; flex-direction:column; z-index:60;
}
.sidebar-header{padding:20px 18px 12px; border-bottom:1px solid var(--border);}
.sidebar-title{font-size:.95rem; font-weight:700; margin:0 0 2px; color:var(--text);}
.sidebar-sub{font-size:.78rem; color:var(--muted); margin:0;}
.search-box{padding:12px 14px; border-bottom:1px solid var(--border);}
.search-input{
  width:100%; padding:9px 12px; font-size:.9rem; color:var(--text);
  background:var(--bg); border:1px solid var(--border); border-radius:8px;
  font-family:inherit;
}
.search-input::placeholder{color:var(--muted);}
.nav-scroll{overflow-y:auto; padding:10px 8px 28px; flex:1;}
.nav-list{list-style:none; margin:0; padding:0;}
.nav-list li{margin:1px 0;}
.nav-link{
  display:block; padding:6px 10px; border-radius:7px; color:var(--muted);
  font-size:.86rem; line-height:1.35; border-left:3px solid transparent;
}
.nav-link:hover{background:var(--bg); color:var(--text); text-decoration:none;}
.nav-h3 .nav-link{padding-left:24px; font-size:.82rem;}
.nav-link.active{
  background:var(--note-bg); color:var(--accent-strong); font-weight:600;
  border-left-color:var(--accent);
}
.nav-list li.search-hide{display:none;}
.nav-link mark{background:#f4e08a; color:var(--text); padding:0 1px; border-radius:2px;}
.nav-empty{padding:10px 12px; color:var(--muted); font-size:.82rem; font-style:italic; display:none;}

/* ---- topbar (mobile) ---- */
.topbar{
  display:none; position:sticky; top:0; z-index:50; background:var(--surface);
  border-bottom:1px solid var(--border); padding:10px 14px; align-items:center; gap:12px;
}
.hamburger{
  display:inline-flex; align-items:center; justify-content:center; width:42px; height:42px;
  background:var(--bg); border:1px solid var(--border); border-radius:8px; cursor:pointer;
  color:var(--text); font-size:1.1rem;
}
.topbar-title{font-size:.92rem; font-weight:600; color:var(--text);}
.scrim{
  display:none; position:fixed; inset:0; background:rgba(31,36,48,.4); z-index:55;
}

/* ---- main ---- */
.content{margin-left:var(--sidebar-w); flex:1; min-width:0; width:100%;}
.main-inner{max-width:none; margin:0; padding:40px 48px 96px;}
main h1{font-size:1.9rem; line-height:1.25; margin:.2em 0 .5em; letter-spacing:-.01em;}
main h2{
  font-size:1.45rem; line-height:1.3; margin:2.2em 0 .6em; padding-bottom:.3em;
  border-bottom:1px solid var(--border); scroll-margin-top:84px;
}
main h3{font-size:1.15rem; margin:1.8em 0 .5em; scroll-margin-top:84px; color:var(--accent-strong);}
main h4{font-size:1.02rem; margin:1.4em 0 .4em; color:var(--text); scroll-margin-top:84px;}
main p{margin:.7em 0;}
main ul,main ol{margin:.6em 0; padding-left:1.5em;}
main li{margin:.3em 0;}
main hr{border:none; border-top:1px solid var(--border); margin:2.4em 0;}
main strong{font-weight:700;}
:not(pre)>code{
  background:var(--code-bg); color:var(--code-text); padding:.12em .38em; border-radius:5px;
  font-family:'JetBrains Mono','Fira Code','Cascadia Code',ui-monospace,Consolas,monospace;
  font-size:.86em; border:1px solid var(--border);
}

/* ---- tables ---- */
.table-scroll{overflow-x:auto; margin:1.1em 0; border:1px solid var(--border); border-radius:10px;}
main table{border-collapse:collapse; width:100%; font-size:.9rem; background:var(--surface);}
main th,main td{text-align:left; padding:9px 12px; border-bottom:1px solid var(--border); vertical-align:top;}
main th{background:#eef1f4; color:var(--text); font-weight:600; white-space:nowrap;}
main tr:last-child td{border-bottom:none;}
main tbody tr:nth-child(even){background:#fafbfc;}
main td code,main th code{white-space:nowrap;}

/* ---- callouts ---- */
.callout{
  display:flex; gap:12px; margin:1.2em 0; padding:14px 16px 14px 14px;
  border-left:5px solid var(--note-bd); border-radius:10px; background:var(--note-bg);
  box-shadow:var(--shadow);
}
.callout-icon{font-size:1.15rem; line-height:1.5; flex:0 0 auto; user-select:none;}
.callout-body{min-width:0; flex:1;}
.callout-body>:first-child{margin-top:0;}
.callout-body>:last-child{margin-bottom:0;}
.callout-boa-pratica{background:var(--good-bg); border-left-color:var(--good-bd);}
.callout-ma-pratica{background:var(--bad-bg); border-left-color:var(--bad-bd);}
.callout-atencao{background:var(--warn-bg); border-left-color:var(--warn-bd);}
.callout-nota{background:var(--note-bg); border-left-color:var(--note-bd);}
.callout .code-wrap{margin:.7em 0;}

/* plain (non-callout) blockquote */
main blockquote{
  margin:1.2em 0; padding:10px 16px; border-left:4px solid var(--border);
  background:var(--surface); color:var(--muted); border-radius:8px;
}
main blockquote>:first-child{margin-top:0;}
main blockquote>:last-child{margin-bottom:0;}

/* ---- code ---- */
.code-wrap{
  margin:1.2em 0; border:1px solid var(--border); border-radius:10px; overflow:hidden;
  background:var(--code-bg); box-shadow:var(--shadow);
}
.code-toolbar{
  display:flex; align-items:center; justify-content:space-between;
  padding:6px 10px 6px 14px; background:#e9ebef; border-bottom:1px solid var(--border);
}
.code-lang{font-size:.72rem; letter-spacing:.06em; text-transform:uppercase; color:var(--muted); font-weight:600;}
.copy-btn{
  font-family:inherit; font-size:.78rem; color:var(--accent-strong); background:var(--surface);
  border:1px solid var(--border); border-radius:7px; padding:4px 12px; cursor:pointer;
}
.copy-btn:hover{background:#fff; border-color:var(--accent);}
.copy-btn.copied{color:#fff; background:var(--good-bd); border-color:var(--good-bd);}
pre.code-block{
  margin:0; padding:14px 16px; overflow-x:auto; background:var(--code-bg);
  color:var(--code-text); font-size:.84rem; line-height:1.55;
}
pre.code-block code{
  font-family:'JetBrains Mono','Fira Code','Cascadia Code',ui-monospace,Consolas,monospace;
  background:none; border:none; padding:0; white-space:pre; color:inherit;
}
.tok-kw{color:var(--kw); font-weight:600;}
.tok-str{color:var(--str);}
.tok-num{color:var(--num);}
.tok-com{color:var(--com); font-style:italic;}
.tok-ann{color:var(--ann); font-weight:600;}
.tok-tag{color:var(--tag); font-weight:600;}
.tok-attr{color:var(--attr);}

/* ---- responsive ---- */
@media (max-width:960px){
  .sidebar{
    transform:translateX(-100%); transition:transform .22s ease;
    width:min(86vw,330px); box-shadow:var(--shadow);
  }
  .sidebar.open{transform:translateX(0);}
  .content{margin-left:0;}
  .topbar{display:flex;}
  body.drawer-open .scrim{display:block;}
  .main-inner{padding:24px 18px 80px;}
}
@media (prefers-reduced-motion:reduce){
  html{scroll-behavior:auto;}
  .sidebar{transition:none;}
  /* WCAG 2.2.2: neutralize the looping animated Mermaid edge dashes. The runtime adds
     `animation: ...` to edge <path>s inside the rendered SVG; pre.mermaid * catches them. */
  pre.mermaid *{animation:none !important;}
}

/* ---- mermaid diagrams ---- */
/* hidden until the runtime swaps <pre class="mermaid"> text for an <svg>, so the
   raw diagram source never flashes; mermaid adds data-processed once rendered. */
pre.mermaid{
  margin:1.4em 0; padding:8px 4px; overflow-x:auto; text-align:center;
  background:transparent; border:none; box-shadow:none;
  font-family:'JetBrains Mono','Fira Code','Cascadia Code',ui-monospace,Consolas,monospace;
  font-size:.84rem; line-height:1.5; color:var(--muted); visibility:hidden;
}
pre.mermaid[data-processed]{visibility:visible;}
pre.mermaid svg{max-width:100%; height:auto;}

/* ---- references section (issue #14: language-NEUTRAL, generated from docs/references.md) ---- */
/* Lives inside <main> AFTER both lang-panes; it is NOT a .lang-pane, so it always shows in
   either language. A top divider sets it apart as the shared bibliography below the guide. */
.references{border-top:2px solid var(--border); margin-top:1em;}
.references .main-inner{padding-top:24px;}

/* ---- language toggle + bilingual panes ---- */
/* Both languages are embedded; only the active language's panes show. The active language
   is set on <html data-lang="..."> early (no FOUC) and each inactive <section.lang-pane> /
   nav pane carries the [hidden] attribute (toggled by JS — valid single-<main> + a11y). The
   explicit rule out-specifies any later display rule so [hidden] always wins. */
.lang-pane[hidden]{display:none !important;}
/* Visibility follows <html data-lang> so the early head-script switches panes BEFORE paint
   (no FOUC) and a JS-disabled client still sees exactly one language (the static pt-BR
   default). setLang() additionally sets [hidden] on the inactive panes for a11y semantics. */
html[data-lang="pt"] .lang-pane[data-lang="en"]{display:none;}
html[data-lang="en"] .lang-pane[data-lang="pt"]{display:none;}
.lang-toggle{display:flex; gap:6px; padding:10px 14px 2px;}
.lang-btn{
  flex:1; font-family:inherit; font-size:.78rem; font-weight:600; cursor:pointer;
  color:var(--muted); background:var(--bg); border:1px solid var(--border);
  border-radius:7px; padding:6px 8px;
}
.lang-btn:hover{color:var(--text); border-color:var(--accent);}
.lang-btn[aria-pressed="true"]{color:#fff; background:var(--accent); border-color:var(--accent);}
"""


JS = r"""
(function(){
  "use strict";

  // ---- copy buttons ----
  document.querySelectorAll('.copy-btn').forEach(function(btn){
    var label = btn.textContent;  // original 'Copy'/'Copiar', snapshot once at wire time
    btn.addEventListener('click', function(){
      if(btn.classList.contains('copied')) return;  // ignore re-clicks during the feedback window
      var wrap = btn.closest('.code-wrap');
      var code = wrap ? wrap.querySelector('pre code') : null;
      if(!code) return;
      var text = code.textContent;
      var done = function(){
        btn.textContent = btn.getAttribute('data-copied') || 'Copied!';
        btn.classList.add('copied');
        setTimeout(function(){ btn.textContent = label; btn.classList.remove('copied'); }, 1600);
      };
      if(navigator.clipboard && navigator.clipboard.writeText){
        navigator.clipboard.writeText(text).then(done, function(){ fallbackCopy(text, done); });
      } else { fallbackCopy(text, done); }
    });
  });
  function fallbackCopy(text, done){
    var ta = document.createElement('textarea');
    ta.value = text; ta.setAttribute('readonly','');
    ta.style.position='absolute'; ta.style.left='-9999px';
    document.body.appendChild(ta); ta.select();
    try{ document.execCommand('copy'); done(); }catch(e){}
    document.body.removeChild(ta);
  }

  // ---- drawer (mobile) ----
  var sidebar = document.getElementById('sidebar');
  var hamb = document.getElementById('hamburger');
  var scrim = document.getElementById('scrim');
  function openDrawer(){ sidebar.classList.add('open'); document.body.classList.add('drawer-open');
    if(hamb) hamb.setAttribute('aria-expanded','true'); }
  function closeDrawer(){ sidebar.classList.remove('open'); document.body.classList.remove('drawer-open');
    if(hamb) hamb.setAttribute('aria-expanded','false'); }
  if(hamb) hamb.addEventListener('click', function(){
    sidebar.classList.contains('open') ? closeDrawer() : openDrawer();
  });
  if(scrim) scrim.addEventListener('click', closeDrawer);
  document.addEventListener('keydown', function(e){ if(e.key === 'Escape') closeDrawer(); });

  // ---- nav click: on mobile, close drawer ----
  var navLinks = Array.prototype.slice.call(document.querySelectorAll('.nav-link'));
  navLinks.forEach(function(a){
    a.addEventListener('click', function(){ if(window.innerWidth <= 960) closeDrawer(); });
  });

  // ---- active section via IntersectionObserver ----
  var byId = {};
  navLinks.forEach(function(a){ byId[a.getAttribute('data-target')] = a; });
  var headings = navLinks.map(function(a){ return document.getElementById(a.getAttribute('data-target')); })
                         .filter(Boolean);
  var visible = {};
  function setActive(id){
    navLinks.forEach(function(a){ a.classList.toggle('active', a.getAttribute('data-target') === id); });
  }
  function recomputeActive(){
    var ids = Object.keys(visible);
    if(ids.length){
      ids.sort(function(x,y){ return visible[x] - visible[y]; });
      setActive(ids[0]);
      return;
    }
    // none intersecting: pick the last RENDERED heading above the viewport top. The
    // getClientRects() guard skips the hidden language pane's headings (display:none ->
    // empty rect list), so a hidden-pane heading is never marked active.
    var current = null;
    for(var i=0;i<headings.length;i++){
      if(!headings[i].getClientRects().length) continue;
      if(headings[i].getBoundingClientRect().top < 120) current = headings[i].id;
    }
    if(current) setActive(current);
  }
  if('IntersectionObserver' in window && headings.length){
    var obs = new IntersectionObserver(function(entries){
      entries.forEach(function(en){
        if(en.isIntersecting) visible[en.target.id] = en.boundingClientRect.top;
        else delete visible[en.target.id];
      });
      recomputeActive();
    }, { rootMargin:'-80px 0px -70% 0px', threshold:[0,1] });
    headings.forEach(function(h){ obs.observe(h); });
  }

  // ---- client-side search: filter sidebar nav ----
  var search = document.getElementById('nav-search');
  var emptyMsg = document.getElementById('nav-empty');
  var items = Array.prototype.slice.call(document.querySelectorAll('.nav-list li'));
  function norm(s){
    return s.normalize('NFD').replace(/[̀-ͯ]/g,'').toLowerCase();
  }
  items.forEach(function(li){
    var a = li.querySelector('.nav-link');
    li.dataset.text = a ? a.textContent : '';
    li.dataset.norm = norm(li.dataset.text);
  });
  function clearMarks(){
    items.forEach(function(li){
      var a = li.querySelector('.nav-link');
      if(a && a.querySelector('mark')) a.textContent = li.dataset.text;
    });
  }
  if(search){
    search.addEventListener('input', function(){
      var q = search.value.trim();
      var nq = norm(q);
      clearMarks();
      var shown = 0;
      items.forEach(function(li){
        var pane = li.closest('.nav-lang');
        if(pane && pane.hasAttribute('hidden')){ li.classList.remove('search-hide'); return; }
        if(!nq){ li.classList.remove('search-hide'); shown++; return; }
        var hit = li.dataset.norm.indexOf(nq) !== -1;
        li.classList.toggle('search-hide', !hit);
        if(hit){
          shown++;
          var a = li.querySelector('.nav-link');
          var idx = li.dataset.norm.indexOf(nq);
          if(a && idx !== -1){
            var t = li.dataset.text;
            a.innerHTML = escapeHtml(t.slice(0,idx)) + '<mark>' +
              escapeHtml(t.slice(idx, idx+q.length)) + '</mark>' + escapeHtml(t.slice(idx+q.length));
          }
        }
      });
      if(emptyMsg) emptyMsg.style.display = shown ? 'none' : 'block';
    });
  }
  function escapeHtml(s){
    return s.replace(/[&<>"']/g, function(c){
      return ({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'})[c];
    });
  }

  // ---- syntax highlighter (deliberately simple, self-contained) ----
  var KW = {
    java: ['abstract','assert','boolean','break','byte','case','catch','char','class','const','continue','default','do','double','else','enum','extends','final','finally','float','for','goto','if','implements','import','instanceof','int','interface','long','native','new','package','private','protected','public','return','short','static','strictfp','super','switch','synchronized','this','throw','throws','transient','try','var','void','volatile','while','true','false','null','record','yield','sealed','permits'],
    mqsc: ['DEFINE','ALTER','SET','DISPLAY','DIS','DELETE','REFRESH','REPLACE','QLOCAL','QREMOTE','QALIAS','QMODEL','QMGR','CHANNEL','AUTHINFO','AUTHREC','CHLAUTH','PROFILE','OBJTYPE','QUEUE','GROUP','AUTHADD','TYPE','BLOCKUSER','USERMAP','USERLIST','CLNTUSER','USERSRC','MAP','MCAUSER','ACTION','SECURITY','DESCR','DEFPSIST','BOTHRESH','BOQNAME','CONNAUTH','AUTHTYPE','IDPWOS','CHCKCLNT','REQUIRED','ADOPTCTX','YES','NO','DEADQ','PUT','SETALL','SSLCAUTH','SSLCIPH'],
    yaml: [],
    xml: [],
    text: []
  };

  function esc(s){
    return s.replace(/&/g,'&amp;').replace(/</g,'&lt;').replace(/>/g,'&gt;');
  }

  function hlGeneric(code, lang){
    var kws = KW[lang] || [];
    var kwSet = {};
    kws.forEach(function(k){ kwSet[lang==='mqsc'?k.toUpperCase():k] = true; });
    var out = '';
    var i = 0, n = code.length;
    var lineCommentTokens = (lang==='mqsc') ? ['*','--'] : ['//'];
    function atLineStartStar(pos){
      // mqsc '*' comment only when '*' is first non-space char of a line
      var j = pos-1;
      while(j>=0 && code[j] !== '\n'){ if(code[j] !== ' ' && code[j] !== '\t') return false; j--; }
      return true;
    }
    while(i < n){
      var c = code[i];
      // block comment /* ... */  (java/xml-ish)
      if((lang==='java') && c==='/' && code[i+1]==='*'){
        var e = code.indexOf('*/', i+2); if(e===-1) e=n; else e+=2;
        out += '<span class="tok-com">'+esc(code.slice(i,e))+'</span>'; i=e; continue;
      }
      // xml comment <!-- -->
      if(lang==='xml' && c==='<' && code.substr(i,4)==='<!--'){
        var e2 = code.indexOf('-->', i+4); if(e2===-1) e2=n; else e2+=3;
        out += '<span class="tok-com">'+esc(code.slice(i,e2))+'</span>'; i=e2; continue;
      }
      // line comments
      if(c==='/' && code[i+1]==='/' && lineCommentTokens.indexOf('//')!==-1){
        var el = code.indexOf('\n', i); if(el===-1) el=n;
        out += '<span class="tok-com">'+esc(code.slice(i,el))+'</span>'; i=el; continue;
      }
      if(lang==='mqsc' && c==='-' && code[i+1]==='-'){
        var el2 = code.indexOf('\n', i); if(el2===-1) el2=n;
        out += '<span class="tok-com">'+esc(code.slice(i,el2))+'</span>'; i=el2; continue;
      }
      if(lang==='mqsc' && c==='*' && atLineStartStar(i)){
        var el3 = code.indexOf('\n', i); if(el3===-1) el3=n;
        out += '<span class="tok-com">'+esc(code.slice(i,el3))+'</span>'; i=el3; continue;
      }
      if(lang==='yaml' && c==='#'){
        var el4 = code.indexOf('\n', i); if(el4===-1) el4=n;
        out += '<span class="tok-com">'+esc(code.slice(i,el4))+'</span>'; i=el4; continue;
      }
      // strings
      if(c==='"' || c==="'"){
        var q=c, j=i+1;
        while(j<n){ if(code[j]==='\\'){ j+=2; continue; } if(code[j]===q){ j++; break; } j++; }
        out += '<span class="tok-str">'+esc(code.slice(i,j))+'</span>'; i=j; continue;
      }
      // java annotations
      if(lang==='java' && c==='@' && /[A-Za-z_]/.test(code[i+1]||'')){
        var j2=i+1; while(j2<n && /[A-Za-z0-9_]/.test(code[j2])) j2++;
        out += '<span class="tok-ann">'+esc(code.slice(i,j2))+'</span>'; i=j2; continue;
      }
      // xml tags
      if(lang==='xml' && c==='<'){
        var j3=i+1; while(j3<n && code[j3] !== '>') {
          if(code[j3]==='"'||code[j3]==="'"){ var qq=code[j3]; j3++; while(j3<n && code[j3]!==qq) j3++; }
          j3++;
        }
        if(j3<n) j3++;
        out += '<span class="tok-tag">'+esc(code.slice(i,j3))+'</span>'; i=j3; continue;
      }
      // yaml keys (word followed by ':')
      if(lang==='yaml' && /[A-Za-z0-9_.-]/.test(c)){
        var j4=i; while(j4<n && /[A-Za-z0-9_.-]/.test(code[j4])) j4++;
        var word = code.slice(i,j4);
        var rest = code.slice(j4);
        if(/^\s*:/.test(rest) && /[A-Za-z]/.test(word)){
          out += '<span class="tok-attr">'+esc(word)+'</span>'; i=j4; continue;
        }
        out += esc(word); i=j4; continue;
      }
      // identifiers / keywords
      if(/[A-Za-z_]/.test(c)){
        var j5=i; while(j5<n && /[A-Za-z0-9_]/.test(code[j5])) j5++;
        var w = code.slice(i,j5);
        var key = (lang==='mqsc') ? w.toUpperCase() : w;
        if(kwSet[key]) out += '<span class="tok-kw">'+esc(w)+'</span>';
        else out += esc(w);
        i=j5; continue;
      }
      // numbers
      if(/[0-9]/.test(c)){
        var j6=i; while(j6<n && /[0-9._xXa-fA-F]/.test(code[j6])) j6++;
        out += '<span class="tok-num">'+esc(code.slice(i,j6))+'</span>'; i=j6; continue;
      }
      out += esc(c); i++;
    }
    return out;
  }

  document.querySelectorAll('pre.code-block').forEach(function(pre){
    var code = pre.querySelector('code');
    if(!code) return;
    var lang = pre.getAttribute('data-lang') || 'text';
    var raw = code.textContent;
    if(lang === 'text'){ return; } // no highlighting for plain text
    try { code.innerHTML = hlGeneric(raw, lang); } catch(e){ /* leave raw on error */ }
  });

  // ---- bilingual language toggle (issue #13) ----
  // Page-level chrome strings per language. Keys MUST match across languages.
  var I18N = {
    en: {
      htmlLang: 'en',
      title: 'Production Guide — Java 25 / Micronaut 4 + IBM MQ (JMS 2.0, COA/COD)',
      skip: 'Skip to content',
      topbarTitle: 'IBM MQ Guide · JMS 2.0 · Micronaut 4',
      sidebarTitle: 'IBM MQ + JMS 2.0 Guide',
      sidebarSub: 'Java 25 · Micronaut 4 · COA/COD',
      searchPlaceholder: 'Search sections…',
      searchAria: 'Search the guide sections',
      navEmpty: 'No sections found.',
      sidebarAria: 'Guide navigation',
      hamburgerAria: 'Open navigation menu'
    },
    pt: {
      htmlLang: 'pt-BR',
      title: 'Guia de Produção — Java 25 / Micronaut 4 + IBM MQ (JMS 2.0, COA/COD)',
      skip: 'Pular para o conteúdo',
      topbarTitle: 'Guia IBM MQ · JMS 2.0 · Micronaut 4',
      sidebarTitle: 'Guia IBM MQ + JMS 2.0',
      sidebarSub: 'Java 25 · Micronaut 4 · COA/COD',
      searchPlaceholder: 'Buscar seções…',
      searchAria: 'Buscar seções do guia',
      navEmpty: 'Nenhuma seção encontrada.',
      sidebarAria: 'Navegação do guia',
      hamburgerAria: 'Abrir menu de navegação'
    }
  };
  (function assertKeyParity(){
    var ek = Object.keys(I18N.en).sort().join(','), pk = Object.keys(I18N.pt).sort().join(',');
    if(ek !== pk){ console.error('I18N key mismatch:', ek, '!=', pk); }
  })();

  var STORAGE_KEY = 'guide-lang';
  var htmlEl = document.documentElement;
  function setText(id, val){ var el = document.getElementById(id); if(el) el.textContent = val; }
  function setAttr(id, attr, val){ var el = document.getElementById(id); if(el) el.setAttribute(attr, val); }

  function renderMermaid(pane){
    if(!pane || !window.mermaid) return;
    var nodes = Array.prototype.slice.call(pane.querySelectorAll('pre.mermaid:not([data-processed])'));
    if(!nodes.length) return;
    try {
      if(window.mermaid.run){ window.mermaid.run({ nodes: nodes }); }
      else if(window.mermaid.init){ window.mermaid.init(undefined, nodes); }
    } catch(e){ /* leave raw on error */ }
  }

  function setLang(lang){
    if(lang !== 'en' && lang !== 'pt') lang = 'pt';
    var t = I18N[lang];
    htmlEl.setAttribute('lang', t.htmlLang);
    htmlEl.setAttribute('data-lang', lang);
    Array.prototype.slice.call(document.querySelectorAll('.lang-pane')).forEach(function(p){
      if(p.getAttribute('data-lang') === lang) p.removeAttribute('hidden');
      else p.setAttribute('hidden', '');
    });
    document.title = t.title;
    setText('skip-link', t.skip);
    setText('topbar-title', t.topbarTitle);
    setText('sidebar-title', t.sidebarTitle);
    setText('sidebar-sub', t.sidebarSub);
    setText('nav-empty', t.navEmpty);
    setAttr('sidebar', 'aria-label', t.sidebarAria);
    setAttr('hamburger', 'aria-label', t.hamburgerAria);
    var s = document.getElementById('nav-search');
    if(s){ s.setAttribute('placeholder', t.searchPlaceholder); s.setAttribute('aria-label', t.searchAria); s.value = ''; }
    if(typeof items !== 'undefined'){
      items.forEach(function(li){ li.classList.remove('search-hide'); });
      if(typeof clearMarks === 'function') clearMarks();
    }
    if(emptyMsg) emptyMsg.style.display = 'none';
    Array.prototype.slice.call(document.querySelectorAll('.lang-btn')).forEach(function(b){
      b.setAttribute('aria-pressed', b.getAttribute('data-lang') === lang ? 'true' : 'false');
    });
    try { localStorage.setItem(STORAGE_KEY, lang); } catch(e){}
    renderMermaid(document.querySelector('main .lang-pane[data-lang="' + lang + '"]'));
    // the revealed pane's headings haven't fired an intersection event yet and the old
    // pane's observer entries are stale — reset and recompute the active nav for the new pane.
    if(typeof visible !== 'undefined'){ for(var vk in visible){ delete visible[vk]; } }
    if(typeof recomputeActive === 'function') recomputeActive();
  }

  Array.prototype.slice.call(document.querySelectorAll('.lang-btn')).forEach(function(b){
    b.addEventListener('click', function(){ setLang(b.getAttribute('data-lang')); });
  });

  var initial = (window.__guideLang === 'en' || window.__guideLang === 'pt') ? window.__guideLang : null;
  if(!initial){
    try { initial = localStorage.getItem(STORAGE_KEY); } catch(e){ initial = null; }
  }
  if(initial !== 'en' && initial !== 'pt'){
    var nl = (navigator.language || 'pt').toLowerCase();
    initial = nl.indexOf('en') === 0 ? 'en' : 'pt';
  }
  setLang(initial);
})();
"""


# Mermaid bootstrap. Kept as its OWN string (NOT f-string-interpolated) so the literal
# braces in the config object do not collide with the page f-string. theme:'base' +
# transparent background lets our per-node classDef fills show over the light page;
# lineColor/fontFamily match the doc chrome (body font stack).
# securityLevel:'loose' — chosen as the known-good level (NOT empirically minimized: no
# browser/jsdom is available to A/B the tighter levels at build time). 'loose' reliably
# renders everything these 4 diagrams need at once: htmlLabels (the <br/> line breaks in
# node/edge labels), per-node classDef fills over the light page, edge animation
# (animate:true, available since Mermaid 11.3 — we ship 11.15), AND the inline
# %%{init: ...}%% theme directive in the sequenceDiagram (block 3), which 'strict'
# sanitizes away. 'sandbox' is avoided because its iframe wrapper complicates SVG sizing
# and inheriting our page colors. (DOMPurify under 'strict' does permit a bare <br>, so
# the precise reason 'loose' wins is the %%{init}%% directive + not browser-testable, not
# a blanket "strict strips <br>".)
MERMAID_INIT = r"""
(function(){
  if(!window.mermaid){ return; }
  // startOnLoad:false — the bilingual page embeds BOTH languages; diagrams inside a
  // [hidden] pane have no layout box (getBBox -> 0) and would render collapsed and never
  // re-render on reveal. setLang() lazy-calls mermaid.run() on the active pane instead.
  window.mermaid.initialize({
    startOnLoad: false,
    theme: 'base',
    securityLevel: 'loose',
    themeVariables: {
      background: 'transparent',
      lineColor: '#5b6472',
      fontFamily: "'Inter',system-ui,-apple-system,'Segoe UI',Roboto,sans-serif"
    }
  });
})();
"""


def wrap_tables(markup):
    """Wrap every <table>…</table> in a responsive <div class="table-scroll"> container.

    Extracted from the duplicated inline re.sub calls in render_references() and render_lang()
    (m2 DRY cleanup). Both callers use identical regex flags (re.DOTALL) and an identical
    substitution pattern, so a single helper is the canonical form. The param is named
    ``markup`` (not ``html``) so it does not shadow the module-level ``import html``."""
    return re.sub(
        r"(<table>.*?</table>)",
        r'<div class="table-scroll">\1</div>',
        markup,
        flags=re.DOTALL,
    )


def render_references():
    """Render the canonical bibliography (docs/references.md, issue #14) to an HTML fragment.

    Uses a FRESH markdown.Markdown instance (markdown.Markdown is stateful — a per-call instance
    avoids id/state carry-over from the guide renders) with only tables/attr_list/sane_lists:
    deliberately NO TocExtension, so the references headings get no auto-generated ids and stay
    OUT of the nav (which keys off body <h2/h3 id>). Tables are wrapped in <div class=
    "table-scroll"> exactly like render_lang does, so they reuse the same responsive styling.
    The returned fragment is interpolated into a language-NEUTRAL <section> (see build()); it is
    NOT a lang-pane and is NOT part of either language body fragment, so the four parity gates
    never see it."""
    src = REFERENCES.read_text(encoding="utf-8")
    md = markdown.Markdown(extensions=["tables", "attr_list", "sane_lists"])
    body = md.convert(src)
    body = wrap_tables(body)
    return body


def render_lang(src_path, chrome):
    """Run the full single-language pipeline on one source file. Returns the rendered body
    fragment (the <main> inner HTML), its nav, the source-side measurements, the callout
    tally, and the healed source markdown — one dict per language."""
    raw = src_path.read_text(encoding="utf-8")
    src, healed = heal_soft_wraps(raw)
    src, list_seps = heal_blockquote_lists(src)
    measured = measure_source(src)
    measured["healed"] = healed
    measured["list_seps"] = list_seps

    # a FRESH Markdown instance per language (markdown.Markdown is stateful: toc/ids).
    md = markdown.Markdown(
        extensions=[
            "tables",
            "fenced_code",
            "attr_list",
            TocExtension(slugify=gh_slugify, toc_depth="2-4"),
            "sane_lists",
            "md_in_html",
        ]
    )
    body = md.convert(src)

    # DIVERT ```mermaid to the diagram path BEFORE decorate_code (no copy button / no
    # syntax highlighter on diagrams).
    body, mermaid_out = divert_mermaid(body)
    measured["mermaid_out"] = mermaid_out

    body, callout_counts = split_callouts(body)
    body = decorate_code(body, chrome)
    body = wrap_tables(body)
    nav_html = build_nav(body)
    return {
        "body": body,
        "nav": nav_html,
        "measured": measured,
        "callouts": callout_counts,
        "src": src,
    }


def build():
    """Render BOTH languages and assemble one standalone bilingual page. Both languages are
    embedded; the active one is chosen at load (stored > browser > pt-BR fallback) and
    switched in-page. Returns (page_html, en_render, pt_render)."""
    mermaid_runtime = ensure_mermaid_runtime()
    en = render_lang(GUIDE_EN, CHROME["en"])
    pt = render_lang(GUIDE_PT, CHROME["pt"])

    # locals (avoid quote-nesting inside the f-string)
    pt_nav, en_nav = pt["nav"], en["nav"]
    pt_body, en_body = pt["body"], en["body"]
    # Canonical bibliography (issue #14), rendered once into a language-NEUTRAL section below.
    refs_html = render_references()

    # The static shell renders pt-BR chrome (the documented fallback). The no-FOUC head
    # script sets <html data-lang> before paint; setLang() then corrects panes ([hidden]) +
    # chrome strings (JS I18N) to the detected/stored language.
    page = f"""<!DOCTYPE html>
<html lang="pt-BR" data-lang="pt">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light">
<title>Guia de Produção — Java 25 / Micronaut 4 + IBM MQ (JMS 2.0, COA/COD)</title>
<script>
/* No-FOUC language pick: stored choice, else browser language, else pt-BR fallback. Set on
   <html> before <body> paints so html[data-lang] CSS shows the correct panes immediately. */
(function(){{
  try{{
    var s = null;
    try{{ s = localStorage.getItem('guide-lang'); }}catch(e){{}}
    if(s !== 'en' && s !== 'pt'){{
      var nl = (navigator.language || 'pt').toLowerCase();
      s = nl.indexOf('en') === 0 ? 'en' : 'pt';
    }}
    window.__guideLang = s;
    document.documentElement.setAttribute('data-lang', s);
    document.documentElement.setAttribute('lang', s === 'en' ? 'en' : 'pt-BR');
  }}catch(e){{}}
}})();
</script>
<style>{CSS}</style>
</head>
<body>
<a id="skip-link" class="skip-link" href="#conteudo">Pular para o conteúdo</a>

<div class="topbar">
  <button id="hamburger" class="hamburger" type="button" aria-label="Abrir menu de navegação"
          aria-controls="sidebar" aria-expanded="false">&#9776;</button>
  <span id="topbar-title" class="topbar-title">Guia IBM MQ · JMS 2.0 · Micronaut 4</span>
</div>
<div id="scrim" class="scrim" aria-hidden="true"></div>

<div class="layout">
  <nav id="sidebar" class="sidebar" aria-label="Navegação do guia">
    <div class="sidebar-header">
      <p id="sidebar-title" class="sidebar-title">Guia IBM MQ + JMS 2.0</p>
      <p id="sidebar-sub" class="sidebar-sub">Java 25 · Micronaut 4 · COA/COD</p>
    </div>
    <div class="lang-toggle" role="group" aria-label="Language / Idioma">
      <button type="button" class="lang-btn" data-lang="pt" aria-pressed="true">Português</button>
      <button type="button" class="lang-btn" data-lang="en" aria-pressed="false">English</button>
    </div>
    <div class="search-box">
      <input id="nav-search" class="search-input" type="search"
             placeholder="Buscar seções…" aria-label="Buscar seções do guia" autocomplete="off">
    </div>
    <div class="nav-scroll">
      <div class="nav-lang lang-pane" data-lang="pt">
{pt_nav}
      </div>
      <div class="nav-lang lang-pane" data-lang="en">
{en_nav}
      </div>
      <p id="nav-empty" class="nav-empty" role="status">Nenhuma seção encontrada.</p>
    </div>
  </nav>

  <div class="content">
    <main id="conteudo" class="main" tabindex="-1">
      <section class="lang-pane" data-lang="pt" aria-label="Conteúdo do guia (português)">
        <div class="main-inner">
{pt_body}
        </div>
      </section>
      <section class="lang-pane" data-lang="en" aria-label="Guide content (English)">
        <div class="main-inner">
{en_body}
        </div>
      </section>
      <section class="references" aria-label="References">
        <div class="main-inner">
{refs_html}
        </div>
      </section>
    </main>
  </div>
</div>

<script>{mermaid_runtime}</script>
<script>{MERMAID_INIT}</script>
<script>{JS}</script>
</body>
</html>
"""

    OUT.write_text(page, encoding="utf-8")
    return page, en, pt


def count_output(out: str):
    # Count structural markup only: strip the inlined <style> and <script> bodies first
    # so literals inside the ~3MB Mermaid runtime (e.g. its own `<table>` / `<pre class=
    # "mermaid">` strings) and inside our own CSS comments do NOT inflate the tallies.
    markup = re.sub(r"<script\b[^>]*>.*?</script>", "<script></script>", out, flags=re.DOTALL)
    markup = re.sub(r"<style\b[^>]*>.*?</style>", "<style></style>", markup, flags=re.DOTALL)
    headings = len(re.findall(r"<h[123]\b", markup))
    tables = len(re.findall(r"<table\b", markup))
    code_blocks = len(re.findall(r'<pre class="code-block"[^>]*><code', markup))
    mermaid_blocks = len(re.findall(r'<pre class="mermaid">', markup))
    callouts = {t: len(re.findall(rf'class="callout callout-{t}"', markup)) for t in EMOJI.values()}
    ids = set(re.findall(r'id="([^"]+)"', markup))
    return {
        "headings": headings,
        "tables": tables,
        "code_blocks": code_blocks,
        "mermaid_blocks": mermaid_blocks,
        "callouts": callouts,
        "callouts_total": sum(callouts.values()),
        "ids": ids,
    }


def parity_lang(lang, src, fragment, measured, callout_counts):
    """Per-language source-markdown <-> rendered-fragment parity (the original single-language
    gate, scoped to ONE language's body fragment). Page-level external-dep/standalone is
    checked once in standalone_scan(); cross-language sync in cross_lang_parity()."""
    out = fragment
    out_counts = count_output(fragment)
    lines = []
    ok = True

    def check(label, a, b):
        nonlocal ok
        good = a == b
        ok = ok and good
        lines.append(f"  {'OK ' if good else 'XX '} {label}: source={a} output={b}")
        return good

    lines.append(f"[{lang}] PER-LANGUAGE PARITY (source markdown vs rendered fragment)")
    lines.append(f"  --  soft-wrap-split bold markers healed: {measured.get('healed', 0)}")
    lines.append(f"  --  blockquote list separators inserted: {measured.get('list_seps', 0)}")
    check("headings (h1-h3)", measured["headings"], out_counts["headings"])
    check("tables", measured["tables"], out_counts["tables"])
    check("code blocks", measured["code_blocks"], out_counts["code_blocks"])
    # mermaid diagrams: every source ```mermaid fence must become a <pre class="mermaid">
    # in the output (and the divert tally must agree). code_blocks above EXCLUDES these,
    # so the count auto-adjusts: the 4 ex-```text-now-```mermaid blocks moved here.
    check("mermaid blocks", measured["mermaid_blocks"], out_counts["mermaid_blocks"])
    check("mermaid blocks (divert tally)", measured["mermaid_blocks"], measured.get("mermaid_out", -1))
    for t in ("boa-pratica", "ma-pratica", "atencao", "nota"):
        check(f"callout {t}", measured["callouts"][t], out_counts["callouts"][t])
    check("callout total", measured["callouts_total"], out_counts["callouts_total"])
    # split function's own tally must agree with the regex scan of the output
    check("callout total (splitter tally)", measured["callouts_total"], sum(callout_counts.values()))

    # anchor resolution: every ](#...) in source must have a matching id in output
    missing = sorted(a for a in measured["anchors"] if a not in out_counts["ids"])
    anchor_ok = not missing
    ok = ok and anchor_ok
    lines.append(
        f"  {'OK ' if anchor_ok else 'XX '} in-document anchors resolved: "
        f"{len(measured['anchors']) - len(missing)}/{len(measured['anchors'])}"
        + ("" if anchor_ok else f"  MISSING={missing}")
    )

    # no code content dropped: every fenced block's text must survive in output.
    # Compare total non-whitespace chars of source fenced code vs output <code> in code-block.
    # ```mermaid fences are DIVERTED to <pre class="mermaid"> (not .code-block), so they are
    # excluded on BOTH sides: skip them here too, else source != output and parity FAILs.
    src_lines = src.split("\n")
    in_fence = False
    fence_is_mermaid = False
    src_code_chars = 0
    for ln in src_lines:
        if ln.startswith("```"):
            if not in_fence:
                fence_is_mermaid = ln.strip().startswith("```mermaid")
            else:
                fence_is_mermaid = False
            in_fence = not in_fence
            continue
        if in_fence and not fence_is_mermaid:
            src_code_chars += len(re.sub(r"\s+", "", ln))
    out_code_chars = 0
    for m in re.finditer(r'<pre class="code-block"[^>]*><code[^>]*>(.*?)</code></pre>', out, flags=re.DOTALL):
        txt = html.unescape(re.sub(r"<[^>]+>", "", m.group(1)))
        out_code_chars += len(re.sub(r"\s+", "", txt))
    code_chars_ok = src_code_chars == out_code_chars
    ok = ok and code_chars_ok
    lines.append(
        f"  {'OK ' if code_chars_ok else 'XX '} code content preserved (non-ws chars): "
        f"source={src_code_chars} output={out_code_chars}"
    )

    # callout text not dropped: concatenated source callout non-ws chars vs output callout
    # non-ws chars (both sides strip the callout-emoji glyphs, compared on the HEALED source).
    src_callout_chars = _source_callout_chars(src)
    out_callout_chars = _output_callout_chars(out)
    callout_chars_ok = src_callout_chars == out_callout_chars
    ok = ok and callout_chars_ok
    lines.append(
        f"  {'OK ' if callout_chars_ok else 'XX '} callout content preserved (fingerprint chars): "
        f"source={src_callout_chars} output={out_callout_chars}"
    )

    # list STRUCTURE rendered: every source list-item line (outside fences, incl. inside
    # blockquotes/callouts) must become a real <li> in MAIN content. Scoped to <main> so
    # the sidebar nav <li> are excluded. This is the structural analog of the stray-'*'
    # gate and catches lists that failed to render (e.g. packed under a callout title).
    in_fence = False
    src_items = 0
    for ln in src.split("\n"):
        if ln.startswith("```"):
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if _list_item_kind(ln):
            src_items += 1
    mstart = out.find("<main")
    mend = out.find("</main>")
    main_html = out[mstart:mend] if mstart != -1 and mend != -1 else out
    out_items = len(re.findall(r"<li\b", main_html))
    items_ok = src_items == out_items
    ok = ok and items_ok
    lines.append(
        f"  {'OK ' if items_ok else 'XX '} list items rendered (<li> in <main>): "
        f"source={src_items} output={out_items}"
    )

    # stray-asterisk scan: in correctly-rendered prose every '*' is consumed as a tag.
    # A surviving '*' OUTSIDE <pre>/<code> flags a broken bold/emphasis marker (the
    # soft-wrap seams). All legit '*' uses (MQRO_*, TLS_RSA_*, *MQADMIN, diagrams) live
    # inside code/fences, which we strip first.
    prose = re.sub(r"<style\b.*?</style>", "", out, flags=re.DOTALL)
    prose = re.sub(r"<script\b.*?</script>", "", prose, flags=re.DOTALL)
    prose = re.sub(r"<pre\b.*?</pre>", "", prose, flags=re.DOTALL)
    prose = re.sub(r"<code\b.*?</code>", "", prose, flags=re.DOTALL)
    prose = re.sub(r"<[^>]+>", "", prose)
    stray = prose.count("*")
    stray_ok = stray == 0
    ok = ok and stray_ok
    lines.append(
        f"  {'OK ' if stray_ok else 'XX '} stray '*' in rendered prose (broken markers): {stray}"
        + ("" if stray_ok else "  -> soft-wrap-split emphasis not healed")
    )

    lines.append(f"  [{lang}] language parity: {'PASS' if ok else 'FAIL'}")
    return ok, "\n".join(lines)


def cross_lang_parity(m_en, m_pt):
    """EN <-> pt-BR STRUCTURAL sync gate (the bilingual heart of issue #13): both sources MUST
    share identical section / callout / code / mermaid / table structure (labels localized,
    structure identical). Anchors are language-specific slugs and are NOT cross-compared."""
    lines = ["CROSS-LANGUAGE STRUCTURAL PARITY (EN source <-> pt-BR source)"]
    ok = True

    def check(label, a, b):
        nonlocal ok
        good = a == b
        ok = ok and good
        lines.append(f"  {'OK ' if good else 'XX '} {label}: en={a} pt={b}")

    check("headings", m_en["headings"], m_pt["headings"])
    check("tables", m_en["tables"], m_pt["tables"])
    check("code blocks", m_en["code_blocks"], m_pt["code_blocks"])
    check("mermaid blocks", m_en["mermaid_blocks"], m_pt["mermaid_blocks"])
    for t in ("boa-pratica", "ma-pratica", "atencao", "nota"):
        check(f"callout {t}", m_en["callouts"][t], m_pt["callouts"][t])
    check("callout total", m_en["callouts_total"], m_pt["callouts_total"])

    # ordered structure signature: the STRONG check — catches a reorder, an H2<->H3 re-level,
    # a dropped table ROW, or a swapped block, all of which leave the scalar counts above
    # unchanged but shift the document-order token sequence.
    se, sp = m_en.get("structure", []), m_pt.get("structure", [])
    struct_ok = se == sp
    ok = ok and struct_ok
    if struct_ok:
        lines.append(f"  OK  ordered structure signature: {len(se)} tokens identical")
    else:
        n = min(len(se), len(sp))
        diff_at = next((i for i in range(n) if se[i] != sp[i]), n)
        ven = se[diff_at] if diff_at < len(se) else None
        vpt = sp[diff_at] if diff_at < len(sp) else None
        lines.append(
            f"  XX  ordered structure signature: en={len(se)} pt={len(sp)} tokens; "
            f"first divergence at #{diff_at}: en={ven} pt={vpt}"
        )

    lines.append(f"  cross-language sync: {'PASS' if ok else 'FAIL'}")
    return ok, "\n".join(lines)


def standalone_scan(page):
    """Whole-page external-dependency scan: the combined bilingual page must remain standalone
    (ZERO real external resource loads). Run ONCE on the final page (NOT per fragment).

    The inlined ~3MB Mermaid runtime is full of http(s) LITERALS (SVG xmlns, license URLs) that
    are NOT resource loads, so we flag only GENUINE external resource LOADS and scan with the
    inlined <script> BODY blanked (opening tag KEPT, so a real <script src="https://..."> is
    still caught). <style> bodies are NOT blanked — real CSS @import/url(http) loads live there.
    XML-namespace URIs and <a href="https://..."> citation links are excluded by construction."""
    lines = ["STANDALONE SCAN (final combined page — real external resource loads only)"]
    scan_src = re.sub(r"(<script\b[^>]*>).*?(</script>)", r"\1\2", page, flags=re.DOTALL)
    real_load_res = [
        (r'<link\b[^>]*\brel=["\']?stylesheet["\']?[^>]*\bhref=["\']?https?://', "link[stylesheet] href=http"),
        (r'<link\b[^>]*\bhref=["\']?https?://[^>]*\brel=["\']?stylesheet', "link[stylesheet] href=http"),
        (r'<script\b[^>]*\bsrc=["\']?https?://', "script src=http"),
        (r'<(?:img|iframe|audio|video|source|track|embed)\b[^>]*\bsrc=["\']?https?://', "media src=http"),
        (r'@import\b[^;]*\bhttps?://', "@import http"),
        (r'url\(\s*["\']?https?://', "css url(http)"),
        (r'<(?:link|script|img|iframe|audio|video|source|track|embed)\b[^>]*\b(?:src|href)=["\']?https?://(?:[^"\'>\s]*\.)?(?:googleapis\.com|gstatic\.com|jsdelivr\.net|unpkg\.com|cdnjs\.cloudflare\.com)', "cdn host in resource src/href"),
    ]
    found = {}
    for pat, label in real_load_res:
        c = len(re.findall(pat, scan_src, flags=re.IGNORECASE | re.DOTALL))
        if c:
            found[label] = found.get(label, 0) + c
    ext_ok = not found
    lines.append(
        f"  {'OK ' if ext_ok else 'XX '} external-dependency scan (real resource loads only): "
        + ("ZERO external refs" if ext_ok else f"FOUND {found}")
    )
    return ext_ok, "\n".join(lines)


def _fingerprint(s: str) -> str:
    """Alphanumeric fingerprint: drop EVERY non-word char (markdown delimiters **, *,
    backticks, list '-'/'1.', ':', emoji, all punctuation/whitespace) symmetrically.
    Comparing fingerprints kills the entire markdown-tokenization mismatch class — what
    remains is the actual prose, so source-markdown vs rendered-HTML can be compared
    for content preservation regardless of how the markup tokenized."""
    return re.sub(r"\W", "", s, flags=re.UNICODE)


def _source_callout_chars(src: str) -> int:
    """Alphanumeric-fingerprint length of all callout (emoji-bearing blockquote) prose."""
    lines = src.split("\n")
    total = 0
    run = []
    has_emoji = False

    def flush():
        nonlocal total, run, has_emoji
        if has_emoji:
            joined = []
            for r in run:
                body = r
                if body.startswith(">"):
                    body = body[1:]
                    if body.startswith(" "):
                        body = body[1:]
                joined.append(body)
            text = " ".join(joined)
            # Drop the callout emoji glyphs FIRST: the output already stripped them in
            # split_callouts, AND the base codepoint 'ℹ' (U+2139) is matched by \w, so it
            # would survive the fingerprint asymmetrically if not removed here.
            for e in ("✅", "❌", "⚠️", "ℹ️"):
                text = text.replace(e, "")
            total += len(_fingerprint(text))
        run = []
        has_emoji = False

    # A blockquote run continues through non-'>' lazy-continuation lines until a BLANK
    # line (CommonMark lazy continuation). It only flushes on a truly empty line.
    in_quote = False
    for ln in lines:
        if ln.startswith(">"):
            in_quote = True
            run.append(ln)
            if re.match(r"^> (✅|❌|⚠️|ℹ️)", ln):
                has_emoji = True
        elif in_quote and ln.strip() != "":
            run.append(ln)
        else:
            in_quote = False
            flush()
    flush()
    return total


def _balanced_div(s: str, start: int) -> int:
    """Given index of a '<div' opening tag, return the index just past its matching </div>."""
    depth = 0
    i = start
    n = len(s)
    tag_re = re.compile(r"<(/?)div\b[^>]*>", re.IGNORECASE)
    while i < n:
        m = tag_re.search(s, i)
        if not m:
            return n
        if m.group(1):  # closing
            depth -= 1
            if depth == 0:
                return m.end()
        else:
            depth += 1
        i = m.end()
    return n


def _output_callout_chars(out: str) -> int:
    """Sum non-ws prose chars inside every callout-body, with proper div balancing
    so nested code-wrap/table-scroll divs don't cause over- or under-capture."""
    total = 0
    for m in re.finditer(r'<div class="callout-body">', out):
        body_start = m.start()
        end = _balanced_div(out, body_start)
        seg = out[m.end():end]
        # trim the trailing </div> of callout-body itself
        seg = re.sub(r"</div>\s*$", "", seg, count=1)
        txt = html.unescape(re.sub(r"<[^>]+>", "", seg))
        for e in ("✅", "❌", "⚠️", "ℹ️"):  # symmetric with source (base 'ℹ' survives \w)
            txt = txt.replace(e, "")
        total += len(_fingerprint(txt))
    return total


def assembled_page_check(page, en, pt):
    """Close the loop between WHAT WAS MEASURED and WHAT WAS WRITTEN: extract each language's
    <section class="lang-pane"> from the final page bytes and confirm it carries that language's
    OWN rendered body (and not the other's). Catches an f-string pane swap / duplication / drop
    in build() — a class of bug the per-fragment and count gates cannot see."""
    lines = ["ASSEMBLED-PAGE INTEGRITY (each pane carries its own language's rendered body)"]
    ok = True

    def section_inner(lang):
        m = re.search(
            r'<section class="lang-pane" data-lang="' + lang + r'"[^>]*>(.*?)</section>',
            page, flags=re.DOTALL,
        )
        return m.group(1) if m else ""

    en_sec, pt_sec = section_inner("en"), section_inner("pt")
    for label, good in (
        ("en pane present", bool(en_sec)),
        ("pt pane present", bool(pt_sec)),
        ("en pane holds the EN body", en["body"] in en_sec),
        ("en pane does NOT hold the PT body", pt["body"] not in en_sec),
        ("pt pane holds the PT body", pt["body"] in pt_sec),
        ("pt pane does NOT hold the EN body", en["body"] not in pt_sec),
    ):
        ok = ok and good
        lines.append(f"  {'OK ' if good else 'XX '} {label}")
    return ok, "\n".join(lines)


def _measured_of(path):
    """Healed-source structural measurement, identical to what render_lang() feeds the gate."""
    raw = path.read_text(encoding="utf-8")
    s, _ = heal_soft_wraps(raw)
    s, _ = heal_blockquote_lists(s)
    return measure_source(s), s


def run_self_test():
    """Prove the cross-language gate is NOT vacuous: it PASSES synced sources and FAILS a battery
    of injected mismatches. Exercises the SAME cross_lang_parity() main() guards with — including
    a count-invariant H2->H3 re-level that ONLY the ordered structure signature can catch."""
    m_en, _ = _measured_of(GUIDE_EN)
    m_pt, pt = _measured_of(GUIDE_PT)
    src_lines = pt.split("\n")

    def first_line(pred, transform):
        """Return (mutated_src, applied) after transforming the first line matching pred."""
        out, done = [], False
        for ln in src_lines:
            if not done and pred(ln):
                done = True
                new = transform(ln)
                if new is not None:
                    out.append(new)
                continue
            out.append(ln)
        return "\n".join(out), done

    mutations = [
        ("heading-drop",
         *first_line(lambda l: l.startswith("## "), lambda l: None)),
        ("table-row-drop",
         *first_line(lambda l: bool(re.match(r"^\|[\s:|-]+\|\s*$", l)) and "-" in l, lambda l: None)),
        ("mermaid->code retype",
         *first_line(lambda l: l.strip().startswith("```mermaid"),
                     lambda l: l.replace("```mermaid", "```text", 1))),
        ("H2->H3 re-level (count-invariant)",
         *first_line(lambda l: l.startswith("## ") and not l.startswith("### "), lambda l: "#" + l)),
        ("callout boa->ma flip",
         *first_line(lambda l: l.startswith("> ✅"), lambda l: l.replace("✅", "❌", 1))),
    ]

    synced_ok, _ = cross_lang_parity(m_en, m_pt)
    print("SELF-TEST: bilingual cross-language parity gate")
    print(f"  synced EN/pt sources -> {'PASS' if synced_ok else 'FAIL'}  (expect PASS)")
    all_caught = True
    for name, mutated, applied in mutations:
        if not applied:
            all_caught = False
            print(f"  inject {name:34s} -> SKIPPED (mutation not applicable!)")
            continue
        ok, _ = cross_lang_parity(m_en, measure_source(mutated))
        if ok:  # a mutated (divergent) source that still PASSes = a gate blind spot
            all_caught = False
        print(f"  inject {name:34s} -> {'PASS' if ok else 'FAIL'}  (expect FAIL)")
    passed = synced_ok and all_caught
    print("SELF-TEST: " + ("OK — gate passes synced sources and fails on every injected mismatch"
                           if passed else "BROKEN — gate did not behave as required"))
    sys.exit(0 if passed else 1)


def main():
    if "--self-test" in sys.argv:
        run_self_test()
        return
    page, en, pt = build()
    reports, ok = [], True
    for lang, data in (("en", en), ("pt", pt)):
        o, rep = parity_lang(lang, data["src"], data["body"], data["measured"], data["callouts"])
        reports.append(rep)
        ok = ok and o
    o2, rep2 = cross_lang_parity(en["measured"], pt["measured"])
    reports.append(rep2)
    ok = ok and o2
    o3, rep3 = standalone_scan(page)
    reports.append(rep3)
    ok = ok and o3
    o4, rep4 = assembled_page_check(page, en, pt)
    reports.append(rep4)
    ok = ok and o4
    print("\n\n".join(reports))
    print("\n" + ("PARITY: PASS" if ok else "PARITY: FAIL"))
    print(f"\nOutput: {OUT}  ({OUT.stat().st_size} bytes)")
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
