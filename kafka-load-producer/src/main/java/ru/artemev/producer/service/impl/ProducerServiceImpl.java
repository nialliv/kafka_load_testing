package ru.artemev.producer.service.impl;

import com.fasterxml.jackson.databind.JsonMappingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.io.FilenameUtils;
import org.json.CDL;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import ru.artemev.producer.model.ClientModel;
import ru.artemev.producer.service.ProducerService;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
@RequiredArgsConstructor
public class ProducerServiceImpl implements ProducerService {

  @Value("${json.dir.path}")
  private Path jsonDir;

  @Value("${done.dir.path}")
  private Path jsonDirDone;

  @Value("${kafka.topic}")
  private String kafkaTopic;

  private final KafkaTemplate<String, ClientModel> kafkaTemplate;
  private final ObjectMapper objectMapper;

  @Override
  @EventListener(ApplicationReadyEvent.class)
  public void addFileToTopic() {
    log.info("=== Start addFileToTopic ===");

    log.info("Get json dir path " + jsonDir);
    log.info("Get json done dir path " + jsonDirDone);

    if (!jsonDir.toFile().exists()) {
      throw new RuntimeException("File path is not exists");
    }

    if (!jsonDirDone.toFile().exists()) {
      jsonDirDone.toFile().mkdir();
    }
    log.info("Waiting files...");
    while (true) {
      List<File> files =
          Arrays.stream(Objects.requireNonNull(jsonDir.toFile().listFiles()))
              .filter(e -> !e.toString().equals(jsonDirDone.toString()))
              .toList();

      if (files.isEmpty()) continue;

      File file = files.get(0);

      if (file.canRead()) sendFileToKafka(file);
    }
  }

  private void sendFileToKafka(File file) {
    switch (Objects.requireNonNull(FilenameUtils.getExtension(String.valueOf(file)))) {
      case "json" -> {
        log.info("Start send JSON file");

        sendLinesFromJsonFile(file.toPath());
        moveFileToDoneDir(file.toPath());

        log.info("Finish send JSON file");
      }
      case "csv" -> {
        log.info("Start send CSV file");

        sendLinesFromJsonFile(convertCsvFileToJsonFile(file.toPath()));
        // Json File ???????????????????? ?? ?????????? done ???????????? csv
        try {
          Files.delete(file.toPath());
        } catch (IOException e) {
          throw new RuntimeException(e);
        }

        log.info("Finish send CSV file");
      }
      default -> {
        log.warn("I found an unknown file (" + file.getName() + ") and deleted it)");
        file.delete();
      }
    }
  }

  private Path convertCsvFileToJsonFile(Path path) {
    log.info("=== Start convertCsvToPath");
    try {
      InputStream inputStream = new FileInputStream(path.toFile());
      String csvString =
          new BufferedReader(new InputStreamReader(inputStream))
              .lines()
              .collect(Collectors.joining("\n"));
      String json = CDL.toJSONArray(csvString).toString().replace("},{", "},\n{");
      Path pathJson =
          Path.of(jsonDirDone + "/" + FilenameUtils.getBaseName(String.valueOf(path)) + ".json");
      Files.writeString(pathJson, json);
      log.info("=== Finish convertCsvToPath");
      return pathJson;
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
  }

  private void sendLinesFromJsonFile(Path path) {
    log.info("=== Start send lines from " + path.toString());
    try {
      Arrays.stream(objectMapper.readValue(path.toFile(), ClientModel[].class))
          .forEach(
              line -> {
                kafkaTemplate.send(kafkaTopic, UUID.randomUUID().toString(), line);
                //                log.info("Sent " + line + " to kafka");
              });
    }  catch (JsonMappingException e) {
      log.error("Error mapping json...");
      log.info("I'm moving " + path + " to done dir");
      moveFileToDoneDir(path);
    } catch (IOException e) {
      throw new RuntimeException(e);
    }
    log.info("Finish sendJsonLines");
  }

  private void moveFileToDoneDir(Path src) {
    Path doneDir = Path.of(jsonDirDone.toString() + "/" + src.getFileName());
    try {
      if (src.toFile().canRead() && doneDir.toFile().canWrite()) {
        Files.move(src, doneDir);
      } else {
        log.warn("I can't read src " + src + " or write to doneDir...");
      }
    } catch (IOException e) {
      log.error("Exception while moving file: " + e.getMessage());
    }
  }
}
