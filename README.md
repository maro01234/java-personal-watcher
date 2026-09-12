# Java Personal Watcher

Javaで動く、自分専用のWebページ変更監視アプリです。EclipseにMavenプロジェクトとして取り込めます。

## 初版でできること

- 名前・URL・CSSセレクター・巡回間隔（1分〜7日）を登録
- 定期巡回／今すぐ巡回／一時停止／再開
- 初回取得を基準にして、本文が変わった時だけ変更を記録
- SQLiteに巡回日時・エラー・変更前後の本文・未読通知を保存
- Vaadinの日本語画面で履歴を選び、変更前後を左右に表示
- アプリ内の未読件数とトースト通知。画面を閉じていた間の変更も後から確認
- 取得エラー時は最後に成功した本文を保持。次回の比較が壊れません
- 2件まで並列巡回。同じ監視の重複実行を防止

## 必要なもの

- JDK 21以上（EclipseのプロジェクトJREも21以上に設定）
- Maven 3.9以上。EclipseのMaven連携でも実行できます
- 初回起動・ビルド時のインターネット接続（Java／フロントエンド依存関係の取得用）

構成：Java 21、Spring Boot 4.1.1、Vaadin 25.2.7、SQLite JDBC 3.49.1.0、jsoup 1.23.1。
Javaのコンパイル対象は21です。Vaadin 25／Spring Boot 4の組み合わせは[公式アップグレードガイド](https://vaadin.com/docs/latest/upgrading)を参照しています。

## Eclipseで起動する

1. ZIPを展開、またはこの `java-personal-watcher` フォルダを使います。
2. **File → Import → Maven → Existing Maven Projects** を選択します。
3. Root Directoryにこのフォルダを指定し、`pom.xml` を選んでFinish。
4. **Project → Properties → Java Build Path** でJDK 21以上になっていることを確認します。
5. `src/main/java/com/example/watcher/WatcherApplication.java` を開き、**Run As → Java Application**。
6. 初回の依存関係と画面の準備が完了したら、ブラウザで <http://localhost:8080> を開きます。

依存関係が赤くなる場合は **Maven → Update Project** を実行してください。
起動時のWorking directoryはプロジェクトのルートにします。DBはWorking directoryからの相対パスで作成されます。

## ターミナルで起動する

このREADMEがあるフォルダで実行します。

```sh
mvn spring-boot:run
```

終了はCtrl+C。Javaアプリを終了すると巡回も止まります。PCがスリープ中の巡回は行いません。再起動後は期限を過ぎた監視を1回ずつ実行します。

配布用JARを作る場合：

```sh
mvn -Pproduction clean package
java -jar target/java-personal-watcher-0.1.0.jar
```

`production` は画面のフロントエンドもビルドします。初回はNode.js等の取得に時間がかかることがあります。通常の `mvn package` は開発用です。

## まず動作を試す

外部サイトの変更を待たずに、ローカルのサンプルで確認できます。Python 3がある場合、別のターミナルで実行してください。

```sh
python3 -m http.server 8765 --bind 127.0.0.1 --directory demo
```

画面から以下を登録します。

- 監視名：テスト商品の価格
- URL：`http://127.0.0.1:8765/product.html`
- CSSセレクター：`.price`
- 間隔：1分

初回が「基準を保存」になったら `demo/product.html` の `70,000円` を `65,000円` に変更して保存し、「今すぐ」を押します。
「変更あり」と未読1件になり、履歴のCHANGED行で両方の金額が見られます。もう一度巡回すると「変更なし」となり、未読件数は増えません。

## 保存先と設定

設定は `src/main/resources/application.properties` にあります。

| 設定 | 初期値 | 用途 |
| --- | --- | --- |
| `server.address` | `127.0.0.1` | このPCからのみアクセス |
| `server.port` | `8080` | 画面のポート。環境変数 `PORT` でも変更可能 |
| `watcher.db` | `./data/watcher.db` | 環境変数 `WATCHER_DB` で保存先を変更可能 |
| `watcher.scheduler-delay-ms` | `10000` | 巡回期限を確認する間隔（ミリ秒） |

DBをバックアップする時はJavaアプリを終了してから `data` フォルダをコピーしてください。履歴は自動削除しません。画面は監視ごとに最新100件を表示し、それ以前もDBに残ります。変更なし・エラーでは本文を重複保存しません。長期間の運用ではDB容量を確認してください。

ローカル起動は認証なしです。Dockerで公開する場合は認証を必須にしています。ユーザー名は `admin`、パスワードは `WATCHER_AUTH_PASSWORD` または `WATCHER_AUTH_PASSWORD_FILE` で指定します。パスワード未設定の公開用コンテナは起動しません。

## 巡回と通知の仕様

- jsoupが返すHTMLからテキストを抽出します。script／style／noscript／templateは除外します。
- 空行・各行の前後の空白・連続する横方向の空白は正規化します。
- CSSセレクターが未指定ならbody全体を比較。一致要素がない、または本文が空ならエラーです。
- HTML属性・画像・CSSだけの変更は検知しません。広告や日付も本文に入っていれば変更として扱います。
- 取得タイムアウトは15秒、レスポンス上限は2MB。HTTPエラーを「商品が消えた」とは判定しません。
- 巡回間隔は直前の処理完了から計算します。キューが混雑すると遅れることがあります。
- 一時停止は次の実行から有効。すでに通信中の処理は最後まで保存します。
- 通知はアプリ内です。ブラウザ／OSのプッシュ通知、メール、Slack送信は未実装です。
- 画面を開いている間は3秒ごとに更新します。通知の既読は履歴画面で操作します。
- サイトの利用条件と適切な巡回間隔に合わせて使ってください。robots.txtの自動判定やログイン処理は含みません。

## コードの読み方

```text
WatcherApplication  … Spring Boot起動・定期実行の有効化
Watcher             … 監視処理のインターフェース
WebPageWatcher      … HTML取得・セレクター抽出・テキスト正規化
WatchService        … 定期実行・二重実行防止・結果の保存
WatchRepository     … SQLiteの登録・比較・履歴・通知
WatchTarget         … 監視設定を表すrecord
CheckRecord         … 履歴を表すrecord
MainView            … VaadinのJava製管理画面
```

拡張時は `Watcher` の実装を追加します。現在はWEBのみで、サービスの選択キーもWEB固定です。複数種類を有効にする段階で、監視設定にtype列と画面の種類選択を追加してください。

次の拡張候補：価格の数値比較と条件通知、SeleniumでのJavaScript描画ページ取得・スクリーンショット保存、OS通知、ファイル監視。これらは初版には含めていません。

## テスト

```sh
mvn test
```

SQLite再起動時の永続化、初回／不変／変更／失敗時の比較、セレクター抽出、入力エラー、ローカルHTTP取得、巡回の二重実行防止・一時停止を確認します。

## Renderへの公開

`Dockerfile` と無料プランの `render.yaml` を用意しています。常時巡回・SQLiteの永続保存には有料インスタンスと永続ディスクが必要です。無料版は15分アクセスがないと休止し、休止・再起動・再デプロイ時に監視設定と履歴が消えます。

今回のデプロイはパスワードをRenderのSecret Filesに保存し、`WATCHER_AUTH_PASSWORD_FILE=/etc/secrets/watcher-password` で読み込みます。パスワードはGitに含めません。Blueprintから新しく作る場合は `WATCHER_AUTH_PASSWORD` が自動生成されます。ユーザー名は `admin` です。

- HTTPヘルスチェック：`/healthz`（認証不要・ユーザーデータなし）
- 管理画面／履歴／Vaadin RPC：Basic認証で保護
- Docker実行時のリッスン先：`0.0.0.0`、ポートはRenderの `PORT`
- メモリ：Javaヒープ上限256MB、メタスペース上限160MB

[Render無料プランの仕様](https://render.com/docs/free)
