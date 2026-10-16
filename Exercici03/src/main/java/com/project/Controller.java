
package com.project;

import javafx.application.Platform;
import javafx.event.ActionEvent;
import javafx.fxml.FXML;
import javafx.fxml.Initializable;
import javafx.concurrent.Task;
import javafx.scene.control.Button;
import javafx.scene.control.Label;
import javafx.scene.control.ScrollPane;
import javafx.scene.control.TextField;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.layout.HBox;
import javafx.geometry.Pos;
import javafx.stage.FileChooser;

import java.io.BufferedReader;
import java.io.File;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Base64;
import java.util.ResourceBundle;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicBoolean;

import org.json.JSONArray;
import org.json.JSONObject;

public class Controller implements Initializable {

    // Configuració del servidor
    private static final String MODEL_NAME = "Qwen36";
    private static final String MODEL_URL =
            "https://agents.ieti.site/v1/chat/completions";

    // Configura la variable d'entorn OPENAI_API_KEY
    private static final String MODEL_KEY =
            System.getenv("OPENAI_API_KEY");

    @FXML
    private Button buttonCallStream, buttonCallComplete,
            buttonBreak, buttonPicture, buttonSend;

    @FXML
    private TextField inputMessage;

    @FXML
    private VBox chatMessages;

    @FXML
    private ScrollPane scrollPane;

    @FXML
    private ImageView imagePreview;

    private final HttpClient httpClient = HttpClient.newHttpClient();

    private final ExecutorService executorService =
            Executors.newSingleThreadExecutor();

    // Historial de la conversa
    private final JSONArray conversationMessages = new JSONArray();

    private File selectedImage;

    private CompletableFuture<HttpResponse<InputStream>> activeRequest;
    private volatile InputStream currentInputStream;
    private Future<?> activeTask;

    private final AtomicBoolean isCancelled = new AtomicBoolean(false);

    @Override
    public void initialize(URL url, ResourceBundle rb) {
        setButtonsIdle();

        // Els botons antics ja no són necessaris en el nou disseny.
        if (buttonCallStream != null) buttonCallStream.setVisible(false);
        if (buttonCallComplete != null) buttonCallComplete.setVisible(false);
    }

    // --- Enviar missatge ---

    @FXML
    private void callSend(ActionEvent event) {
        if (activeRequest != null && !activeRequest.isDone()) {
            return;
        }

        String prompt = inputMessage.getText().trim();

        if (selectedImage != null && prompt.isEmpty()) {
            prompt = "Describe this image";
        }

        if (prompt.isEmpty()) {
            return;
        }

        File imageFile = selectedImage;
        inputMessage.clear();

        // Mostrar el missatge de l'usuari.
        appendMessage("You", prompt, imageFile);

        JSONObject userMessage;

        if (imageFile != null) {
            try {
                byte[] bytes = Files.readAllBytes(imageFile.toPath());
                String base64 = Base64.getEncoder()
                        .encodeToString(bytes);

                String mimeType = getMimeType(imageFile);
                String dataUrl = "data:" + mimeType + ";base64," + base64;

                JSONArray content = new JSONArray()
                        .put(new JSONObject()
                                .put("type", "text")
                                .put("text", prompt))
                        .put(new JSONObject()
                                .put("type", "image_url")
                                .put("image_url", new JSONObject()
                                        .put("url", dataUrl)));

                userMessage = new JSONObject()
                        .put("role", "user")
                        .put("content", content);

            } catch (Exception e) {
                appendMessage("Xat IETI",
                        "Error reading image: " + e.getMessage(), null);
                return;
            }

            selectedImage = null;
            imagePreview.setImage(null);
            imagePreview.setVisible(false);
            imagePreview.setManaged(false);

        } else {
            userMessage = new JSONObject()
                    .put("role", "user")
                    .put("content", prompt);
        }

        conversationMessages.put(userMessage);

        // Les preguntes amb imatge esperen la resposta completa.
        // Les preguntes de text es mostren en streaming.
        executeRequest(imageFile != null);
    }

    // --- Seleccionar imatge ---

    @FXML
    private void callPicture(ActionEvent event) {
        FileChooser fc = new FileChooser();
        fc.setTitle("Choose an image");

        fc.getExtensionFilters().add(
                new FileChooser.ExtensionFilter(
                        "Images", "*.png", "*.jpg", "*.jpeg",
                        "*.webp", "*.bmp", "*.gif"
                )
        );

        File file = fc.showOpenDialog(
                buttonPicture.getScene().getWindow());

        if (file == null) {
            return;
        }

        selectedImage = file;

        try {
            Image image = new Image(file.toURI().toString());

            imagePreview.setImage(image);
            imagePreview.setVisible(true);
            imagePreview.setManaged(true);

        } catch (Exception e) {
            selectedImage = null;
            appendMessage("Xat IETI", "Could not load image.", null);
        }
    }

    // --- Crear i enviar petició ---

    private void executeRequest(boolean hasImage) {
        isCancelled.set(false);
        setButtonsRunning();

        Label answerLabel = appendMessage(
                "Xat IETI",
                hasImage ? "Thinking..." : "",
                null
        );

        JSONObject body = new JSONObject()
                .put("model", MODEL_NAME)
                .put("messages", conversationMessages)
                .put("stream", !hasImage)
                .put("reasoning_effort", "none");

        HttpRequest.Builder builder = HttpRequest.newBuilder()
                .uri(URI.create(MODEL_URL))
                .header("Content-Type", "application/json");

        if (MODEL_KEY != null && !MODEL_KEY.isBlank()) {
            builder.header("Authorization", "Bearer " + MODEL_KEY);
        }

        HttpRequest request = builder
                .POST(HttpRequest.BodyPublishers.ofString(body.toString()))
                .build();

        activeRequest = httpClient.sendAsync(
                request,
                HttpResponse.BodyHandlers.ofInputStream()
        );

        activeRequest.whenComplete((response, error) -> {
            if (error != null) {
                if (!isCancelled.get()) {
                    Platform.runLater(() -> {
                        answerLabel.setText(
                                "Request failed: " + getErrorMessage(error));
                        setButtonsIdle();
                    });
                }
                return;
            }

            activeTask = executorService.submit(() ->
                    processResponse(response, answerLabel, hasImage));
        });
    }

    // --- Processar resposta del servidor ---

    private void processResponse(
            HttpResponse<InputStream> response,
            Label answerLabel,
            boolean hasImage) {

        StringBuilder answer = new StringBuilder();

        try (InputStream input = response.body()) {
            currentInputStream = input;

            if (response.statusCode() < 200
                    || response.statusCode() >= 300) {

                String errorBody = new String(
                        input.readAllBytes(), StandardCharsets.UTF_8);

                String error = extractOpenAIError(errorBody);

                if (error == null) {
                    error = "HTTP " + response.statusCode()
                            + ": " + errorBody;
                }

                final String message = error;

                Platform.runLater(() -> answerLabel.setText(message));
                return;
            }

            if (hasImage) {
                // La resposta de la imatge es mostra quan està completa.
                String responseBody = new String(
                        input.readAllBytes(), StandardCharsets.UTF_8);

                answer.append(extractOpenAIResponse(responseBody));

                if (!isCancelled.get()) {
                    String result = answer.toString();
                    Platform.runLater(() -> answerLabel.setText(result));
                }

            } else {
                // Streaming: llegir cada fragment SSE.
                BufferedReader reader = new BufferedReader(
                        new InputStreamReader(input, StandardCharsets.UTF_8));

                String line;

                while ((line = reader.readLine()) != null) {
                    if (isCancelled.get()) {
                        break;
                    }

                    if (!line.startsWith("data:")) {
                        continue;
                    }

                    String data = line.substring(5).trim();

                    if (data.equals("[DONE]")) {
                        break;
                    }

                    try {
                        JSONObject json = new JSONObject(data);
                        JSONArray choices = json.optJSONArray("choices");

                        if (choices == null || choices.isEmpty()) {
                            continue;
                        }

                        JSONObject delta = choices.getJSONObject(0)
                                .optJSONObject("delta");

                        if (delta == null) {
                            continue;
                        }

                        String chunk = delta.optString("content", "");

                        if (!chunk.isEmpty()) {
                            answer.append(chunk);
                            String currentText = answer.toString();

                            Platform.runLater(() ->
                                    answerLabel.setText(currentText));
                        }

                    } catch (Exception ignored) {
                        // Ignorar fragments que no continguin JSON vàlid.
                    }
                }
            }

            // Guardar la resposta a l'historial per a preguntes posteriors.
            if (!isCancelled.get() && !answer.isEmpty()) {
                conversationMessages.put(new JSONObject()
                        .put("role", "assistant")
                        .put("content", answer.toString()));
            }

        } catch (Exception e) {
            if (!isCancelled.get()) {
                Platform.runLater(() -> answerLabel.setText(
                        "Error: " + getErrorMessage(e)));
            }

        } finally {
            currentInputStream = null;

            Platform.runLater(this::setButtonsIdle);
        }
    }

    // --- Afegir missatges a la conversa ---

    private Label appendMessage(
            String author, String message, File imageFile) {

        VBox bubble = new VBox(8);
        bubble.setMaxWidth(Double.MAX_VALUE);
        bubble.setStyle(
                "-fx-padding: 12px;"
                + "-fx-background-color: "
                + (author.equals("You") ? "#E8F0FE" : "#F1F1F1")
                + "; -fx-background-radius: 12px;"
        );

        Label name = new Label(author);
        name.setStyle("-fx-font-weight: bold; -fx-font-size: 15px;");

        Label text = new Label(message);
        text.setWrapText(true);
        text.setMaxWidth(650);

        bubble.getChildren().add(name);

        if (imageFile != null) {
            try {
                ImageView thumbnail = new ImageView(
                        new Image(imageFile.toURI().toString()));

                thumbnail.setFitWidth(180);
                thumbnail.setFitHeight(140);
                thumbnail.setPreserveRatio(true);

                bubble.getChildren().add(thumbnail);

            } catch (Exception ignored) {
                // El text del missatge continua visible.
            }
        }

        bubble.getChildren().add(text);
        chatMessages.getChildren().add(bubble);

        Platform.runLater(() -> scrollPane.setVvalue(1.0));

        return text;
    }

    // --- Cancel·lar petició ---

    @FXML
    private void callBreak(ActionEvent event) {
        isCancelled.set(true);

        if (activeRequest != null) {
            activeRequest.cancel(true);
        }

        if (activeTask != null) {
            activeTask.cancel(true);
        }

        try {
            if (currentInputStream != null) {
                currentInputStream.close();
            }
        } catch (Exception ignored) {
        }

        setButtonsIdle();
    }

    // --- Helpers ---

    private String extractOpenAIResponse(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONArray choices = json.optJSONArray("choices");

            if (choices != null && !choices.isEmpty()) {
                JSONObject message = choices.getJSONObject(0)
                        .optJSONObject("message");

                if (message != null) {
                    return message.optString("content", "");
                }
            }

            String error = extractOpenAIError(body);

            if (error != null) {
                return error;
            }

        } catch (Exception e) {
            return "Invalid server response: " + body;
        }

        return "(empty)";
    }

    private String extractOpenAIError(String body) {
        try {
            JSONObject json = new JSONObject(body);
            JSONObject error = json.optJSONObject("error");

            if (error != null) {
                return "Error: " + error.optString(
                        "message", error.toString());
            }
        } catch (Exception ignored) {
        }

        return null;
    }

    private String getErrorMessage(Throwable e) {
        Throwable cause = e;

        while (cause.getCause() != null) {
            cause = cause.getCause();
        }

        return cause.getMessage() != null
                ? cause.getMessage()
                : cause.getClass().getSimpleName();
    }

    private String getMimeType(File file) {
        try {
            String type = Files.probeContentType(file.toPath());

            if (type != null && type.startsWith("image/")) {
                return type;
            }
        } catch (Exception ignored) {
        }

        String name = file.getName().toLowerCase();

        if (name.endsWith(".png")) return "image/png";
        if (name.endsWith(".jpg") || name.endsWith(".jpeg")) {
            return "image/jpeg";
        }
        if (name.endsWith(".webp")) return "image/webp";
        if (name.endsWith(".gif")) return "image/gif";
        if (name.endsWith(".bmp")) return "image/bmp";

        return "application/octet-stream";
    }

    private void setButtonsRunning() {
        buttonSend.setDisable(true);
        buttonPicture.setDisable(true);
        buttonBreak.setDisable(false);
    }

    private void setButtonsIdle() {
        buttonSend.setDisable(false);
        buttonPicture.setDisable(false);
        buttonBreak.setDisable(true);

        activeRequest = null;
        activeTask = null;
    }
}
