package org.tfg.console;

import javafx.application.Application;
import javafx.application.Platform;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.layout.*;
import javafx.stage.Stage;

import java.util.List;
import java.util.Map;

/**
 * Consola de escritorio para el revisor humano (JavaFX). Toda la presentación está en console.css.
 * Misma regla que la API: si la propuesta no pasó el kit, el botón Aprobar está deshabilitado.
 */
public final class ReviewConsoleApp extends Application {
    private static final Map<String, String> STATUS = Map.of(
        "BLOCKED_BY_GATE", "Bloqueada por el kit", "PENDING_REVIEW", "Pendiente de revisión",
        "APPROVED", "Aprobada", "REJECTED", "Rechazada");

    private ReviewApi api;
    private final ListView<Map<String, Object>> list = new ListView<>();
    private final VBox detail = new VBox(16);

    @Override
    public void start(Stage stage) {
        api = new ReviewApi(getParameters().getNamed().getOrDefault("api", "http://localhost:8090"));

        Label title = new Label("Revisión de propuestas de mapeo");
        title.getStyleClass().add("app-title");
        Label note = new Label("Ninguna propuesta llega a producción sin pasar el kit de verificación y una decisión humana.");
        note.getStyleClass().add("muted");
        VBox top = new VBox(4, title, note);
        top.getStyleClass().add("topbar");

        list.getStyleClass().add("ledger");
        list.setCellFactory(v -> new ListCell<>() {
            @Override protected void updateItem(Map<String, Object> p, boolean empty) {
                super.updateItem(p, empty);
                if (empty || p == null) { setGraphic(null); return; }
                Label id = new Label(String.valueOf(p.get("id")));
                id.getStyleClass().add("row-id");
                Label st = new Label(STATUS.get(String.valueOf(p.get("status"))));
                st.getStyleClass().addAll("row-status", "s-" + p.get("status"));
                Label agent = new Label(String.valueOf(p.get("agent")));
                agent.getStyleClass().add("muted");
                setGraphic(new VBox(2, id, st, agent));
            }
        });
        list.getSelectionModel().selectedItemProperty().addListener((o, a, p) -> { if (p != null) load(String.valueOf(p.get("id"))); });
        Label ledgerTitle = new Label("Propuestas");
        ledgerTitle.getStyleClass().add("h2");
        VBox left = new VBox(10, ledgerTitle, list);
        VBox.setVgrow(list, Priority.ALWAYS);
        left.setPrefWidth(300);

        detail.getStyleClass().add("sheet");
        detail.getChildren().add(muted("Elige una propuesta para ver su verificación, los cambios y la evidencia."));
        ScrollPane scroll = new ScrollPane(detail);
        scroll.setFitToWidth(true);
        scroll.getStyleClass().add("sheet-scroll");

        HBox desk = new HBox(24, left, scroll);
        HBox.setHgrow(scroll, Priority.ALWAYS);
        desk.setPadding(new Insets(24));
        BorderPane root = new BorderPane(desk, top, null, null, null);
        Scene scene = new Scene(root, 1180, 780);
        scene.getStylesheets().add(getClass().getResource("console.css").toExternalForm());
        stage.setTitle("Revisión de propuestas de IA · Facturación electrónica");
        stage.setScene(scene);
        stage.show();
        refresh(null);
    }

    private void refresh(String select) {
        run(api::proposals, ps -> {
            list.getItems().setAll(ps.stream().map(MiniJson::obj).toList());
            list.getItems().stream().filter(p -> p.get("id").equals(select)).findFirst()
                .or(() -> list.getItems().stream().findFirst()).ifPresent(p -> list.getSelectionModel().select(p));
        });
    }

    private void load(String id) { run(() -> api.proposal(id), this::render); }

    private void render(Map<String, Object> p) {
        String status = String.valueOf(p.get("status"));
        boolean gate = Boolean.TRUE.equals(p.get("gate_passed"));
        Map<String, Object> v = MiniJson.obj(p.get("verification"));

        Label t = new Label("Propuesta " + p.get("id"));
        t.getStyleClass().add("h2");
        Label stamp = new Label(STATUS.get(status));
        stamp.getStyleClass().addAll("stamp", "s-" + status);
        stamp.setRotate(-4);
        Region spacer = new Region();
        HBox.setHgrow(spacer, Priority.ALWAYS);
        HBox head = new HBox(t, spacer, stamp);
        head.setAlignment(Pos.TOP_LEFT);

        GridPane facts = new GridPane();
        facts.setHgap(32); facts.setVgap(8);
        fact(facts, 0, 0, "Agente", String.valueOf(p.get("agent")));
        fact(facts, 1, 0, "Creada", java.time.OffsetDateTime.parse(String.valueOf(p.get("created_at")))
            .atZoneSameInstant(java.time.ZoneId.systemDefault())
            .format(java.time.format.DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", java.util.Locale.of("es", "CO"))));
        long docs = ((Number) v.getOrDefault("documents", 0L)).longValue();
        long failed = ((Number) v.getOrDefault("failedDocuments", 0L)).longValue();
        fact(facts, 0, 1, "Documentos verificados", (docs - failed) + " de " + docs + " sin hallazgos");
        Map<String, Object> acc = v.get("fieldAccuracy") == null ? Map.of() : MiniJson.obj(v.get("fieldAccuracy"));
        fact(facts, 1, 1, "Exactitud frente al mapeo dorado", acc.isEmpty() ? "—" : acc.get("correct") + " de " + acc.get("fields") + " campos");

        VBox findings = new VBox(8);
        List<Object> ex = MiniJson.list(v.get("examples"));
        if (ex.isEmpty()) findings.getChildren().add(finding(null));
        ex.forEach(f -> findings.getChildren().add(finding(MiniJson.obj(f))));

        TextField reviewer = new TextField();
        reviewer.setPromptText("usuario del revisor");
        TextArea comment = new TextArea();
        comment.setPromptText("Qué revisaste y por qué decides así");
        comment.setPrefRowCount(3);
        Button approve = new Button("Aprobar propuesta");
        approve.getStyleClass().add("btn-approve");
        Button reject = new Button("Rechazar propuesta");
        reject.getStyleClass().add("btn-reject");
        Label msg = new Label();
        msg.getStyleClass().add("form-msg");
        Label gateNote = new Label(gate ? "Pasó el kit. Revisa los cambios y decide."
            : "No se puede aprobar: la propuesta no pasó el kit. Corrígela y vuelve a generarla.");
        gateNote.getStyleClass().add(gate ? "muted" : "blocked");
        approve.setDisable(!gate);
        boolean decided = status.equals("APPROVED") || status.equals("REJECTED");
        if (decided) { approve.setDisable(true); reject.setDisable(true); reviewer.setDisable(true); comment.setDisable(true); }
        approve.setOnAction(e -> decide(p, "APPROVE", reviewer, comment, msg));
        reject.setOnAction(e -> decide(p, "REJECT", reviewer, comment, msg));
        VBox form = new VBox(6, h3("Decisión"), gateNote, new Label("Revisor"), reviewer, new Label("Comentario"), comment,
            new HBox(12, approve, reject), msg);
        form.getStyleClass().add("decision");

        detail.getChildren().setAll(head, facts, h3("Hallazgos del kit"),
            muted("Cada hallazgo cita la sección de la especificación y la huella del documento del que sale la regla."),
            findings, form);
    }

    private void decide(Map<String, Object> p, String d, TextField reviewer, TextArea comment, Label msg) {
        String id = String.valueOf(p.get("id"));
        run(() -> api.decide(id, d, reviewer.getText().trim(), comment.getText().trim()), err -> {
            if (err == null) refresh(id);
            else { msg.setText(err); msg.getStyleClass().setAll("form-msg", "error"); }
        });
    }

    private javafx.scene.Node finding(Map<String, Object> f) {
        if (f == null) {
            Label ok = new Label("Sin hallazgos: la propuesta pasó el kit en ejecución y los fixtures de CI.");
            ok.getStyleClass().addAll("finding", "finding-ok");
            ok.setMaxWidth(Double.MAX_VALUE);
            return ok;
        }
        Label rule = new Label(f.get("ruleId") + "  " + f.get("type"));
        rule.getStyleClass().add("finding-rule");
        Label m = new Label(String.valueOf(f.get("message")));
        m.setWrapText(true);
        String sha = String.valueOf(f.get("sourceSha256"));
        Label src = muted(f.get("source") + " · huella " + sha.substring(0, Math.min(12, sha.length())) + " · regla v" + f.get("ruleVersion"));
        VBox box = new VBox(2, rule, m, src);
        box.getStyleClass().add("finding");
        return box;
    }

    private static void fact(GridPane g, int col, int row, String k, String v) {
        Label key = muted(k);
        Label val = new Label(v);
        val.getStyleClass().add("fact-value");
        g.add(new VBox(2, key, val), col, row);
    }

    private static Label h3(String s) { Label l = new Label(s); l.getStyleClass().add("h3"); return l; }
    private static Label muted(String s) { Label l = new Label(s); l.getStyleClass().add("muted"); l.setWrapText(true); return l; }

    private interface Call<T> { T call() throws Exception; }

    private <T> void run(Call<T> call, java.util.function.Consumer<T> ok) {
        Task<T> task = new Task<>() { @Override protected T call() throws Exception { return call.call(); } };
        task.setOnSucceeded(e -> ok.accept(task.getValue()));
        task.setOnFailed(e -> detail.getChildren().setAll(muted("No se pudo contactar la API de revisión: " + task.getException().getMessage())));
        Thread th = new Thread(task, "api");
        th.setDaemon(true);
        th.start();
    }


    public static void main(String[] args) { launch(args); }
}
