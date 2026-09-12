package com.example.watcher;

import java.net.URI;
import java.util.stream.Collectors;
import org.jsoup.Jsoup;
import org.jsoup.nodes.Document;
import org.springframework.stereotype.Component;

@Component
public class WebPageWatcher implements Watcher {
    @Override public String type() { return "WEB"; }

    public static String validateUrl(String value) {
        try {
            var uri = URI.create(value.strip());
            if (!("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    || uri.getHost() == null || uri.getUserInfo() != null || uri.getFragment() != null)
                throw new IllegalArgumentException();
            return uri.toASCIIString();
        } catch (Exception e) {
            throw new IllegalArgumentException("http(s)のURLを入力してください（認証情報・#は含めません）。");
        }
    }

    @Override public String check(WatchTarget target) throws Exception {
        var response = Jsoup.connect(validateUrl(target.url()))
            .userAgent("JavaPersonalWatcher/0.1 (personal change monitor)")
            .timeout(15000).maxBodySize(2_000_001).execute();
        if (response.bodyAsBytes().length > 2_000_000)
            throw new IllegalArgumentException("ページが上限の2MBを超えています。");
        return extract(response.parse(), target.selector());
    }

    static String extract(Document doc, String selector) {
        doc.select("script,style,noscript,template").remove();
        var elements = doc.select(selector == null || selector.isBlank() ? "body" : selector);
        if (elements.isEmpty()) throw new IllegalArgumentException("CSSセレクターに一致する要素がありません。");
        String text = elements.stream().map(e -> e.wholeText()).collect(Collectors.joining("\n"));
        text = text.lines().map(line -> line.replace('\u00a0', ' ').replaceAll("[\\t ]+", " ").strip())
            .filter(line -> !line.isEmpty()).collect(Collectors.joining("\n"));
        if (text.isEmpty()) throw new IllegalArgumentException("本文が空です。JavaScript描画ページは初版では取得できません。");
        return text;
    }
}
