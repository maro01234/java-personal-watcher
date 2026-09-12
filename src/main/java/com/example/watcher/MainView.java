package com.example.watcher;

import com.vaadin.flow.component.button.Button;
import com.vaadin.flow.component.button.ButtonVariant;
import com.vaadin.flow.component.dialog.Dialog;
import com.vaadin.flow.component.grid.Grid;
import com.vaadin.flow.component.html.*;
import com.vaadin.flow.component.notification.Notification;
import com.vaadin.flow.component.orderedlayout.*;
import com.vaadin.flow.component.textfield.*;
import com.vaadin.flow.router.PageTitle;
import com.vaadin.flow.router.Route;
import com.vaadin.flow.component.dependency.StyleSheet;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;

@Route("")
@PageTitle("Personal Watcher")
@StyleSheet("styles.css")
public class MainView extends VerticalLayout {
    private final WatchRepository repository;
    private final WatchService service;
    private final Grid<WatchTarget> grid = new Grid<>();
    private final Span summary = new Span();
    private final Span unread = new Span();
    private int lastUnread;
    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("MM/dd HH:mm:ss").withZone(ZoneId.systemDefault());

    public MainView(WatchRepository repository, WatchService service) {
        this.repository = repository;
        this.service = service;
        addClassName("workspace");
        var eyebrow = new Span("JAVA PERSONAL WATCHER / WEB WATCH"); eyebrow.addClassName("eyebrow");
        var title = new H1("変化を、見逃さない。");
        var intro = new Paragraph("気になるページを登録。変わった時だけ、ここに届きます。");
        intro.addClassName("muted");
        add(eyebrow, title, intro);
        var stats = new HorizontalLayout(summary, unread); stats.addClassName("stats"); add(stats);

        var name = new TextField("監視名"); name.setPlaceholder("例：気になる中古Mac"); name.setMaxLength(100);
        var url = new TextField("ページURL"); url.setPlaceholder("https://example.com/product");
        var selector = new TextField("CSSセレクター（任意）"); selector.setPlaceholder("例：main / .price / #news");
        selector.setHelperText("空欄なら本文全体。時刻・広告などが変わる場合は範囲を絞ります。");
        var interval = new IntegerField("巡回間隔（分）"); interval.setMin(1); interval.setMax(10080); interval.setValue(60); interval.setStepButtonsVisible(true);
        var submit = new Button("＋ 監視を追加", e -> {
            try {
                if (interval.getValue() == null) throw new IllegalArgumentException("巡回間隔を入力してください。");
                long id = repository.add(name.getValue(), url.getValue(), selector.getValue(), interval.getValue());
                service.checkNow(id); name.clear(); url.clear(); selector.clear(); refresh();
                Notification.show("登録しました。初回の本文を取得しています。");
            } catch (IllegalArgumentException ex) { Notification.show(ex.getMessage(), 5000, Notification.Position.MIDDLE); }
        });
        submit.addThemeVariants(ButtonVariant.LUMO_PRIMARY);
        var form = new com.vaadin.flow.component.formlayout.FormLayout(name, url, selector, interval);
        form.setResponsiveSteps(new com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep("0", 1),
            new com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep("650px", 2));
        var panel = new VerticalLayout(new H2("新しい監視"), form, submit); panel.addClassName("panel"); add(panel);
        var listTitle = new H2("監視リスト");
        var refresh = new Button("表示を更新", e -> refresh());
        var listHeader = new HorizontalLayout(listTitle, refresh); listHeader.setWidthFull(); listHeader.setJustifyContentMode(JustifyContentMode.BETWEEN); listHeader.setAlignItems(Alignment.CENTER);
        add(listHeader);
        grid.addColumn(WatchTarget::name).setHeader("監視名").setAutoWidth(true).setFlexGrow(1);
        grid.addColumn(t -> service.isRunning(t.id()) ? "巡回中…" : !t.enabled() ? "一時停止" : label(t.lastStatus())).setHeader("状態").setAutoWidth(true);
        grid.addColumn(t -> t.intervalMinutes() + "分").setHeader("間隔").setAutoWidth(true);
        grid.addColumn(t -> format(t.lastChecked())).setHeader("最終巡回").setAutoWidth(true);
        grid.addComponentColumn(t -> {
            var check = new Button("今すぐ", e -> {
                Notification.show(service.checkNow(t.id()) ? "巡回を開始しました。" : "巡回中、または待機枠がいっぱいです。"); refresh();
            });
            check.setEnabled(t.enabled() && !service.isRunning(t.id()));
            var pause = new Button(t.enabled() ? "停止" : "再開", e -> { repository.setEnabled(t.id(), !t.enabled()); refresh(); });
            var history = new Button("履歴", e -> showHistory(t));
            return new HorizontalLayout(check, pause, history);
        }).setHeader("操作").setAutoWidth(true).setFlexGrow(0);
        grid.setAllRowsVisible(true); grid.setEmptyStateText("監視はまだありません。上のフォームからURLを追加してください。");
        add(grid);
        var footer = new Paragraph("初回は基準を保存します。変更通知はSQLiteに残り、画面を閉じても巡回は続きます（Javaアプリの起動中）。表示は3秒ごとに更新。時刻はサーバーのタイムゾーンです。");
        footer.addClassName("muted"); add(footer);
        lastUnread = repository.unreadCount(); refresh();
        addAttachListener(e -> {
            e.getUI().setPollInterval(3000);
            e.getUI().addPollListener(p -> {
                int count = repository.unreadCount();
                if (count > lastUnread) Notification.show("ページの変更を検知しました。履歴を確認してください。", 6000, Notification.Position.TOP_END);
                refresh();
            });
        });
    }

    private void refresh() {
        var targets = repository.targets(); grid.setItems(targets);
        summary.setText("監視中 " + targets.stream().filter(WatchTarget::enabled).count() + " / 全 " + targets.size() + " 件");
        lastUnread = repository.unreadCount(); unread.setText("未読の変更 " + lastUnread + " 件");
    }

    private void showHistory(WatchTarget target) {
        var dialog = new Dialog(); dialog.setHeaderTitle(target.name() + " — 巡回履歴");
        dialog.setWidth("1100px"); dialog.setMaxWidth("95vw");
        var url = new Paragraph(target.url()); url.getStyle().set("overflow-wrap", "anywhere");
        var scope = new Paragraph("対象：" + (target.selector().isBlank() ? "本文全体" : target.selector()));
        var records = new Grid<CheckRecord>(); records.setHeight("260px");
        records.addColumn(r -> format(r.checkedAt())).setHeader("日時");
        records.addColumn(r -> (r.unread() ? "● " : "") + label(r.status())).setHeader("結果");
        records.addColumn(CheckRecord::error).setHeader("エラー"); records.setItems(repository.history(target.id()));
        var before = new TextArea("変更前"); var after = new TextArea("変更後 / 初回の本文");
        for (var area : new TextArea[]{before, after}) { area.setReadOnly(true); area.setWidthFull(); area.setHeight("320px"); }
        var comparison = new com.vaadin.flow.component.formlayout.FormLayout(before, after);
        comparison.setWidthFull();
        comparison.setResponsiveSteps(new com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep("0", 1),
            new com.vaadin.flow.component.formlayout.FormLayout.ResponsiveStep("700px", 2));
        var detail = new Paragraph("行を選ぶと、保存した本文を比較できます。最新100件を表示します。");
        records.addSelectionListener(e -> e.getFirstSelectedItem().ifPresent(r -> {
            before.setValue(r.beforeText()); after.setValue(r.afterText());
            detail.setText("ERROR".equals(r.status()) ? r.error() : "UNCHANGED".equals(r.status())
                ? "変更なし：本文の重複保存は省略しています。" : "変更前後の本文（取得したHTMLのテキスト）");
        }));
        var content = new VerticalLayout(url, scope, records, detail, comparison); content.setPadding(false); dialog.add(content);
        dialog.getFooter().add(new Button("この監視の通知を既読にする", e -> {
            repository.markRead(target.id()); records.setItems(repository.history(target.id())); refresh();
        }), new Button("閉じる", e -> dialog.close()));
        dialog.open();
    }

    static String format(Long time) { return time == null ? "—" : TIME.format(Instant.ofEpochMilli(time)); }
    static String label(String status) {
        return switch (status) {
            case "INITIAL" -> "基準を保存";
            case "CHANGED" -> "変更あり";
            case "UNCHANGED" -> "変更なし";
            case "ERROR" -> "取得エラー";
            default -> status;
        };
    }
}
