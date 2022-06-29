/*
 * Copyright (c) 2022 Airbyte, Inc., all rights reserved.
 */

package io.airbyte.integrations.destination.postgres;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.airbyte.commons.json.Jsons;
import io.airbyte.integrations.base.Destination;
import io.airbyte.integrations.base.IntegrationRunner;
import io.airbyte.integrations.base.spec_modification.SpecModifyingDestination;
import io.airbyte.protocol.models.ConnectorSpecification;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class PostgresDestinationStrictEncrypt extends SpecModifyingDestination implements Destination {

  private static final Logger LOGGER = LoggerFactory.getLogger(PostgresDestinationStrictEncrypt.class);

  public PostgresDestinationStrictEncrypt() {
    super(PostgresDestination.sshWrappedDestination());
  }

  @Override
  public ConnectorSpecification modifySpec(final ConnectorSpecification originalSpec) {
    final ConnectorSpecification spec = Jsons.clone(originalSpec);
    ((ObjectNode) spec.getConnectionSpecification().get("properties")).remove("ssl");
    var i = 1;
    List<JsonNode> a = new ArrayList<>();
    while (spec.getConnectionSpecification().get("properties").get("ssl_mode").get("oneOf").has(i)) {
      a.add(spec.getConnectionSpecification().get("properties").get("ssl_mode").get("oneOf").get(i));
      i++;
    }
    ObjectMapper mapper = new ObjectMapper();
    ArrayNode array = mapper.valueToTree(a);
    ((ObjectNode) spec.getConnectionSpecification().get("properties").get("ssl_mode")).remove("oneOf");
    ((ObjectNode) spec.getConnectionSpecification().get("properties").get("ssl_mode")).put("oneOf", array);
    return spec;
  }

  public static void main(final String[] args) throws Exception {
    final Destination destination = new PostgresDestinationStrictEncrypt();
    LOGGER.info("starting destination: {}", PostgresDestinationStrictEncrypt.class);
    new IntegrationRunner(destination).run(args);
    LOGGER.info("completed destination: {}", PostgresDestinationStrictEncrypt.class);
  }

}
