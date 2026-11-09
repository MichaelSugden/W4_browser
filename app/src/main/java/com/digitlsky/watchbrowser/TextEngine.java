package com.digitlsky.watchbrowser;

import android.net.Uri;
import android.text.Html;
import android.text.SpannableStringBuilder;
import android.text.Spanned;
import android.text.style.URLSpan;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Ultra-light "browser engine": downloads a page and turns its HTML into styled text
 * with clickable links. No WebView, no JavaScript, no images, no CSS.
 */
final class TextEngine {

    interface LinkHandler { void open(String url); }

    static final class Page {
        String url;
        String title;
        CharSequence body;
    }

    static final String UA =
            "Mozilla/5.0 (Linux; Android 11; Watch) AppleWebKit/537.36 (KHTML, like Gecko) Mobile Safari/537.36";

    private static final int MAX_BYTES = 1_500_000;   // protect RAM on huge pages
    private static final int MAX_CHARS = 300_000;

    private static final int F = Pattern.CASE_INSENSITIVE | Pattern.DOTALL;
    private static final Pattern TITLE = Pattern.compile("<title[^>]*>(.*?)</title>", F);
    private static final Pattern META_CHARSET = Pattern.compile("<meta[^>]+charset=[\"']?([\\w-]+)", F);
    private static final Pattern STRIP_BLOCKS = Pattern.compile(
            "<!--.*?-->|<(script|style|svg|head|template|iframe|select|button|nav|footer|aside|form|video|audio|picture|object|canvas|math)\\b[^>]*>.*?</\\1\\s*>",
            F);
    private static final Pattern STRIP_TAGS = Pattern.compile("<(img|input|source|track|embed|link|meta|wbr)\\b[^>]*>", F);
    private static final Pattern MANY_NL = Pattern.compile("\\n[ \\t\\u00A0]*(\\n[ \\t\\u00A0]*){2,}");

    private TextEngine() {}

    static Page load(String url, LinkHandler links) throws IOException {
        String cur = url;
        HttpURLConnection c = null;
        int code = 0;
        for (int hop = 0; hop < 8; hop++) {
            c = (HttpURLConnection) new URL(cur).openConnection();
            c.setInstanceFollowRedirects(false);
            c.setConnectTimeout(15000);
            c.setReadTimeout(20000);
            c.setRequestProperty("User-Agent", UA);
            c.setRequestProperty("Accept", "text/html,application/xhtml+xml,text/plain;q=0.9,*/*;q=0.5");
            code = c.getResponseCode();
            if (code >= 300 && code < 400) {
                String loc = c.getHeaderField("Location");
                c.disconnect();
                if (loc == null) break;
                cur = new URL(new URL(cur), loc).toString();
                continue;
            }
            break;
        }

        Page p = new Page();
        p.url = cur;

        String type = c.getContentType() == null ? "" : c.getContentType().toLowerCase(Locale.ROOT);
        if (!type.isEmpty() && !type.startsWith("text/") && !type.contains("html") && !type.contains("xml")) {
            c.disconnect();
            p.title = "Unsupported file";
            p.body = "This link is a file (" + type + ") that text mode can't show.\n\nTry ☰ → Full.";
            return p;
        }

        byte[] raw;
        InputStream in = code >= 400 ? c.getErrorStream() : c.getInputStream();
        try {
            raw = readCapped(in);
        } finally {
            c.disconnect();
        }

        String html = new String(raw, pickCharset(type, raw));

        if (type.startsWith("text/plain")) {
            p.title = Uri.parse(cur).getLastPathSegment();
            p.body = html.length() > MAX_CHARS ? html.substring(0, MAX_CHARS) : html;
            return p;
        }

        Matcher t = TITLE.matcher(html);
        p.title = t.find() ? Html.fromHtml(t.group(1).trim(), Html.FROM_HTML_MODE_LEGACY).toString() : cur;

        html = STRIP_BLOCKS.matcher(html).replaceAll(" ");
        html = STRIP_TAGS.matcher(html).replaceAll("");
        if (html.length() > MAX_CHARS * 3) html = html.substring(0, MAX_CHARS * 3);

        Spanned sp = Html.fromHtml(html, Html.FROM_HTML_MODE_COMPACT);
        SpannableStringBuilder sb = new SpannableStringBuilder(sp);

        // Replace link spans with ones that resolve relative URLs and open in our app.
        final String base = cur;
        for (URLSpan u : sb.getSpans(0, sb.length(), URLSpan.class)) {
            int s = sb.getSpanStart(u), e = sb.getSpanEnd(u);
            sb.removeSpan(u);
            final String target = resolve(base, u.getURL());
            if (target == null) continue;
            sb.setSpan(new android.text.style.ClickableSpan() {
                @Override
                public void onClick(android.view.View w) {
                    links.open(target);
                }
            }, s, e, Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
        }

        // Collapse runs of blank lines (work backwards so indexes stay valid).
        Matcher m = MANY_NL.matcher(sb.toString());
        java.util.ArrayList<int[]> runs = new java.util.ArrayList<>();
        while (m.find()) runs.add(new int[]{m.start(), m.end()});
        for (int i = runs.size() - 1; i >= 0; i--) sb.replace(runs.get(i)[0], runs.get(i)[1], "\n\n");

        // Trim leading whitespace
        int lead = 0;
        while (lead < sb.length() && Character.isWhitespace(sb.charAt(lead))) lead++;
        if (lead > 0) sb.delete(0, lead);
        if (sb.length() > MAX_CHARS) {
            sb.delete(MAX_CHARS, sb.length());
            sb.append("\n\n… (page truncated)");
        }
        if (sb.length() == 0) sb.append("This page has no readable text.\n\nIt probably needs JavaScript — try ☰ → Full.");
        p.body = sb;
        return p;
    }

    /** Resolves relative links and unwraps DuckDuckGo redirect links. */
    static String resolve(String base, String href) {
        if (href == null) return null;
        href = href.trim();
        if (href.isEmpty() || href.startsWith("#") || href.startsWith("javascript:")
                || href.startsWith("mailto:") || href.startsWith("tel:")) return null;
        try {
            String abs = new URL(new URL(base), href).toString();
            Uri u = Uri.parse(abs);
            String host = u.getHost() == null ? "" : u.getHost();
            if (host.endsWith("duckduckgo.com") && "/l/".equals(u.getPath())) {
                String real = u.getQueryParameter("uddg");
                if (real != null) return real;
            }
            return abs.startsWith("http") ? abs : null;
        } catch (Exception e) {
            return null;
        }
    }

    private static byte[] readCapped(InputStream in) throws IOException {
        if (in == null) return new byte[0];
        ByteArrayOutputStream out = new ByteArrayOutputStream(64 * 1024);
        byte[] buf = new byte[16 * 1024];
        int n, total = 0;
        while ((n = in.read(buf)) > 0) {
            out.write(buf, 0, n);
            total += n;
            if (total >= MAX_BYTES) break;
        }
        in.close();
        return out.toByteArray();
    }

    private static Charset pickCharset(String type, byte[] raw) {
        String cs = null;
        int i = type.indexOf("charset=");
        if (i >= 0) cs = type.substring(i + 8).replace("\"", "").split(";")[0].trim();
        if (cs == null) {
            String head = new String(raw, 0, Math.min(raw.length, 4096), Charset.forName("ISO-8859-1"));
            Matcher m = META_CHARSET.matcher(head);
            if (m.find()) cs = m.group(1);
        }
        try {
            if (cs != null) return Charset.forName(cs);
        } catch (Exception ignored) {
        }
        return Charset.forName("UTF-8");
    }
}
