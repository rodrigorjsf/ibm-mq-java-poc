#!/usr/bin/env python3
"""
Phase D build: deterministic single-file standalone HTML from the pt-BR Markdown guide.

Pipeline:
  1. Read SOURCE markdown.
  2. Convert the BODY with python-markdown (tables, fenced_code, attr_list, toc,
     sane_lists, md_in_html) using a GitHub-replica slugify so the in-document
     "Sumário" anchors (#seção-1--... with unicode + double-hyphen) resolve.
  3. Post-process the rendered HTML:
       - split every <blockquote> at each leading callout-emoji boundary into one
         <div class="callout callout-TYPE"> per emoji-led segment (✅/❌/⚠️/ℹ️);
         non-callout blockquotes stay plain <blockquote>.
       - tag <pre><code> blocks with a copy button + run the in-house JS highlighter
         client-side (highlighting is done in the browser; here we only mark blocks).
  4. Wrap everything in a self-contained HTML shell (embedded CSS + JS, NO external
     network dependency) with a fixed sidebar, search, responsive drawer, a11y chrome.
  5. Emit a PARITY REPORT comparing SOURCE vs OUTPUT and sys.exit(1) on any mismatch
     or any external reference found in the output.

Run:
  /home/rodrigo/IBM-MQ/.venv-docs/bin/python /home/rodrigo/IBM-MQ/docs/build-html.py
"""

import html
import re
import sys
import urllib.request
from pathlib import Path

import markdown
from markdown.extensions.toc import TocExtension

DOCS = Path("/home/rodrigo/IBM-MQ/docs")
SRC = DOCS / "guia-ibmmq-jms-micronaut.md"
OUT = DOCS / "index.html"

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

    for ln in lines:
        if ln.startswith("```"):
            if not in_fence:
                # opening fence: classify by info string. ```mermaid blocks are
                # DIAGRAMS (diverted to <pre class="mermaid">), NOT code blocks, so
                # they are tallied separately and excluded from code_blocks.
                fence_is_mermaid = ln.strip().startswith("```mermaid")
                if fence_is_mermaid:
                    mermaid_blocks += 1
                else:
                    fence_blocks += 1
            else:
                fence_is_mermaid = False
            in_fence = not in_fence
            continue
        if in_fence:
            continue
        if re.match(r"^#{1,3} ", ln):
            headings += 1
        # GFM table separator row: |---|:--:|...| (must contain a dash)
        if re.match(r"^\|[\s:|-]+\|\s*$", ln) and "-" in ln:
            table_seps += 1
        m = re.match(r"^> (✅|❌|⚠️|ℹ️)", ln)
        if m:
            callouts[EMOJI[m.group(1)]] += 1

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
def decorate_code(body: str):
    def repl(m: re.Match):
        attrs = m.group(1)
        code = m.group(2)
        lang_m = re.search(r'class="language-([\w-]+)"', attrs)
        lang = lang_m.group(1) if lang_m else "text"
        label = lang
        return (
            '<div class="code-wrap">'
            f'<div class="code-toolbar"><span class="code-lang">{html.escape(label)}</span>'
            '<button type="button" class="copy-btn" '
            'aria-label="Copiar código para a área de transferência">Copiar</button></div>'
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
"""


JS = r"""
(function(){
  "use strict";

  // ---- copy buttons ----
  document.querySelectorAll('.copy-btn').forEach(function(btn){
    btn.addEventListener('click', function(){
      var wrap = btn.closest('.code-wrap');
      var code = wrap ? wrap.querySelector('pre code') : null;
      if(!code) return;
      var text = code.textContent;
      var done = function(){
        var old = btn.textContent;
        btn.textContent = 'Copiado!';
        btn.classList.add('copied');
        setTimeout(function(){ btn.textContent = old; btn.classList.remove('copied'); }, 1600);
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
  if('IntersectionObserver' in window && headings.length){
    var obs = new IntersectionObserver(function(entries){
      entries.forEach(function(en){
        if(en.isIntersecting) visible[en.target.id] = en.boundingClientRect.top;
        else delete visible[en.target.id];
      });
      var ids = Object.keys(visible);
      if(ids.length){
        ids.sort(function(x,y){ return visible[x] - visible[y]; });
        setActive(ids[0]);
      } else {
        // none intersecting: pick the last heading above the viewport top
        var current = null;
        for(var i=0;i<headings.length;i++){
          if(headings[i].getBoundingClientRect().top < 120) current = headings[i].id;
        }
        if(current) setActive(current);
      }
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
  window.mermaid.initialize({
    startOnLoad: true,
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


def build():
    mermaid_runtime = ensure_mermaid_runtime()
    raw = SRC.read_text(encoding="utf-8")
    src, healed = heal_soft_wraps(raw)
    src, list_seps = heal_blockquote_lists(src)
    measured = measure_source(src)
    measured["healed"] = healed
    measured["list_seps"] = list_seps

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

    # DIVERT ```mermaid (rendered by fenced_code as language-mermaid) to the diagram
    # path BEFORE decorate_code, so mermaid blocks become <pre class="mermaid"> and do
    # NOT get a "Copiar" button or the syntax highlighter.
    body, mermaid_out = divert_mermaid(body)
    measured["mermaid_out"] = mermaid_out

    # split callouts, decorate code, wrap tables for horizontal scroll
    body, callout_counts = split_callouts(body)
    body = decorate_code(body)
    body = re.sub(
        r"(<table>.*?</table>)",
        r'<div class="table-scroll">\1</div>',
        body,
        flags=re.DOTALL,
    )

    nav_html = build_nav(body)

    page_title = "Guia de Produção — Java 25 / Micronaut 4 + IBM MQ (JMS 2.0, COA/COD)"

    out = f"""<!DOCTYPE html>
<html lang="pt-BR">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta name="color-scheme" content="light">
<title>{html.escape(page_title)}</title>
<style>{CSS}</style>
</head>
<body>
<a class="skip-link" href="#conteudo">Pular para o conteúdo</a>

<div class="topbar">
  <button id="hamburger" class="hamburger" type="button" aria-label="Abrir menu de navegação"
          aria-controls="sidebar" aria-expanded="false">&#9776;</button>
  <span class="topbar-title">Guia IBM MQ · JMS 2.0 · Micronaut 4</span>
</div>
<div id="scrim" class="scrim" aria-hidden="true"></div>

<div class="layout">
  <nav id="sidebar" class="sidebar" aria-label="Navegação do guia">
    <div class="sidebar-header">
      <p class="sidebar-title">Guia IBM MQ + JMS 2.0</p>
      <p class="sidebar-sub">Java 25 · Micronaut 4 · COA/COD</p>
    </div>
    <div class="search-box">
      <input id="nav-search" class="search-input" type="search"
             placeholder="Buscar seções…" aria-label="Buscar seções do guia" autocomplete="off">
    </div>
    <div class="nav-scroll">
      {nav_html}
      <p id="nav-empty" class="nav-empty">Nenhuma seção encontrada.</p>
    </div>
  </nav>

  <div class="content">
    <main id="conteudo" class="main" tabindex="-1">
      <div class="main-inner">
{body}
      </div>
    </main>
  </div>
</div>

<script>{JS}</script>
<script>{mermaid_runtime}</script>
<script>{MERMAID_INIT}</script>
</body>
</html>
"""

    OUT.write_text(out, encoding="utf-8")
    return out, measured, callout_counts, src


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


def parity_report(src, out, measured, out_counts, callout_counts):
    lines = []
    ok = True

    def check(label, a, b):
        nonlocal ok
        good = a == b
        ok = ok and good
        lines.append(f"  {'OK ' if good else 'XX '} {label}: source={a} output={b}")
        return good

    lines.append("PARITY REPORT (source markdown vs output html)")
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

    # refined external-dependency scan on OUTPUT.
    # The inlined ~3MB Mermaid runtime is full of http(s) LITERALS (SVG xmlns,
    # license URLs) that are NOT resource loads — a crude "http(s) substring" scan
    # would false-positive on every one. So we flag ONLY genuine external RESOURCE
    # LOADS, and we scan with the inlined <script> BODY blanked out:
    #   - <link rel=stylesheet href=https?://...>
    #   - <script src=https?://...>
    #   - @import ... https?://...      (CSS)
    #   - url(https?://...)             (CSS)
    #   - a CDN host (googleapis/jsdelivr/unpkg/cdnjs) appearing in any src=/href=
    # EXCLUDED by construction: XML-namespace URIs (xmlns="http://www.w3.org/..."),
    # bare http(s) literals INSIDE the inlined runtime <script> body (blanked first),
    # and visible-text <a href="https://..."> citation links in the guide body (we only
    # match href on <link>, never on <a>).
    # CRITICAL #1: we blank only the BODY between <script ...> and </script> and KEEP the
    # opening tag's attributes — so a genuine external <script src="https://..."> is still
    # caught (a naive "<script ...>...</script>" -> "" strip would swallow its src= and MISS
    # it).
    # CRITICAL #2: we DO NOT blank <style> bodies — real CSS resource loads (@import,
    # url(http)) live there and MUST be caught. Our own <style> carries no http literal
    # (only the page CSS), so leaving it intact yields no false positive while keeping the
    # CSS-load patterns live. (A CSS comment mentioning "<pre class=...>" is handled by
    # count_output's separate <style> strip, not here, and is not a resource load anyway.)
    # NOTE on the linkage to self-containment: blanking the <script> body means a
    # hypothetical runtime `import("https://cdn...")` hidden in the bundle would be
    # invisible to THIS scan. The real proof of offline self-containment is the
    # build-time audit of the bundle (0 dynamic import(), no from"http, no fetch("http,
    # no CDN host literals) — this scan only guards the PAGE-LEVEL markup we emit.
    scan_src = re.sub(
        r"(<script\b[^>]*>).*?(</script>)", r"\1\2", out, flags=re.DOTALL
    )

    real_load_res = [
        # external stylesheet
        (r'<link\b[^>]*\brel=["\']?stylesheet["\']?[^>]*\bhref=["\']?https?://', "link[stylesheet] href=http"),
        (r'<link\b[^>]*\bhref=["\']?https?://[^>]*\brel=["\']?stylesheet', "link[stylesheet] href=http"),
        # external script
        (r'<script\b[^>]*\bsrc=["\']?https?://', "script src=http"),
        # any other element pulling a remote resource via src=
        (r'<(?:img|iframe|audio|video|source|track|embed)\b[^>]*\bsrc=["\']?https?://', "media src=http"),
        # CSS @import of a remote stylesheet
        (r'@import\b[^;]*\bhttps?://', "@import http"),
        # CSS url(http...) — remote font/image/bg
        (r'url\(\s*["\']?https?://', "css url(http)"),
        # a CDN host inside any src=/href= attribute (defensive: catches odd casings)
        (r'(?:src|href)=["\']?https?://(?:[^"\'>\s]*\.)?(?:googleapis\.com|gstatic\.com|jsdelivr\.net|unpkg\.com|cdnjs\.cloudflare\.com)', "cdn host in src/href"),
    ]
    found = {}
    for pat, label in real_load_res:
        c = len(re.findall(pat, scan_src, flags=re.IGNORECASE | re.DOTALL))
        if c:
            found[label] = found.get(label, 0) + c
    ext_ok = not found
    ok = ok and ext_ok
    lines.append(
        f"  {'OK ' if ext_ok else 'XX '} external-dependency scan (real resource loads only): "
        + ("ZERO external refs" if ext_ok else f"FOUND {found}")
    )

    lines.append("")
    lines.append("PARITY: PASS" if ok else "PARITY: FAIL")
    return ok, "\n".join(lines)


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


def main():
    out, measured, callout_counts, src = build()
    out_counts = count_output(out)
    ok, report = parity_report(src, out, measured, out_counts, callout_counts)
    print(report)
    print(f"\nOutput: {OUT}  ({OUT.stat().st_size} bytes)")
    if not ok:
        sys.exit(1)


if __name__ == "__main__":
    main()
