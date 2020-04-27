/*
 * Copyright 2020 Confluent Inc.
 *
 * Licensed under the Confluent Community License (the "License"); you may not use
 * this file except in compliance with the License.  You may obtain a copy of the
 * License at
 *
 * http://www.confluent.io/confluent-community-license
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OF ANY KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations under the License.
 */

package io.confluent.kafka.schemaregistry.json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.kjetland.jackson.jsonSchema.JsonSchemaConfig;
import com.kjetland.jackson.jsonSchema.JsonSchemaDraft;
import com.kjetland.jackson.jsonSchema.JsonSchemaGenerator;

import java.io.IOException;
import java.io.StringWriter;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Collectors;

import io.confluent.kafka.schemaregistry.annotations.Schema;
import io.confluent.kafka.schemaregistry.client.SchemaRegistryClient;
import io.confluent.kafka.schemaregistry.client.rest.entities.SchemaReference;
import io.confluent.kafka.schemaregistry.json.jackson.Jackson;

public class JsonSchemaUtils {

  private static final ObjectMapper jsonMapper = Jackson.newObjectMapper();

  static final String ENVELOPE_SCHEMA_FIELD_NAME = "schema";
  static final String ENVELOPE_PAYLOAD_FIELD_NAME = "payload";

  public static ObjectNode envelope(JsonSchema schema, JsonNode payload) {
    return envelope(schema.toJsonNode(), payload);
  }

  public static ObjectNode envelope(JsonNode schema, JsonNode payload) {
    ObjectNode result = JsonNodeFactory.instance.objectNode();
    result.set(ENVELOPE_SCHEMA_FIELD_NAME, schema);
    result.set(ENVELOPE_PAYLOAD_FIELD_NAME, payload);
    return result;
  }

  public static boolean isEnvelope(Object object) {
    if (object instanceof JsonNode) {
      JsonNode jsonValue = (JsonNode) object;
      return jsonValue.isObject() && jsonValue.has(ENVELOPE_SCHEMA_FIELD_NAME);
    }
    return false;
  }

  public static JsonSchema copyOf(JsonSchema schema) {
    return schema.copy();
  }

  public static JsonSchema getSchema(Object object) throws IOException {
    return getSchema(object, null);
  }

  public static JsonSchema getSchema(Object object, SchemaRegistryClient client)
          throws IOException {
    if (object == null) {
      return null;
    }
    if (isEnvelope(object)) {
      JsonNode jsonValue = (JsonNode) object;
      return new JsonSchema(jsonValue.get(ENVELOPE_SCHEMA_FIELD_NAME));
    }
    Class<?> cls = object.getClass();
    if (cls.isAnnotationPresent(Schema.class)) {
      Schema schema = (Schema) cls.getAnnotation(Schema.class);
      List<SchemaReference> references = Arrays.stream(schema.refs())
              .map(ref -> new SchemaReference(ref.name(), ref.subject(), ref.version()))
              .collect(Collectors.toList());
      if (client == null) {
        if (!references.isEmpty()) {
          throw new IllegalArgumentException("Cannot resolve schema " + schema.value()
                  + " with refs " + references);
        }
        return new JsonSchema(schema.value());
      } else {
        return (JsonSchema) client.parseSchema(JsonSchema.TYPE, schema.value(), references)
                .orElseThrow(() -> new IOException("Invalid schema " + schema.value()
                        + " with refs " + references));
      }
    }
    JsonSchemaConfig config = JsonSchemaConfig.nullableJsonSchemaDraft4(); // allow nulls
    config = config.withJsonSchemaDraft(JsonSchemaDraft.DRAFT_07);
    JsonSchemaGenerator jsonSchemaGenerator = new JsonSchemaGenerator(jsonMapper, config);
    JsonNode jsonSchema = jsonSchemaGenerator.generateJsonSchema(cls);
    return new JsonSchema(jsonSchema);
  }

  public static Object getValue(Object object) {
    if (object == null) {
      return null;
    }
    if (isEnvelope(object)) {
      JsonNode jsonValue = (JsonNode) object;
      return jsonValue.get(ENVELOPE_PAYLOAD_FIELD_NAME);
    }
    return object;
  }

  public static Object toObject(JsonNode value, JsonSchema schema) throws IOException {
    return toObject(value, schema, true);
  }

  public static Object toObject(JsonNode value, JsonSchema schema, boolean validate)
      throws IOException {
    if (validate) {
      schema.validate(value);
    }
    return envelope(schema, value);
  }

  public static byte[] toJson(Object value) throws IOException {
    if (value == null) {
      return null;
    }
    StringWriter out = new StringWriter();
    jsonMapper.writeValue(out, value);
    String jsonString = out.toString();
    return jsonString.getBytes(StandardCharsets.UTF_8);
  }
}