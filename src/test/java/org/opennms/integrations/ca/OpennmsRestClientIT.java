/*******************************************************************************
 * This file is part of OpenNMS(R).
 *
 * Copyright (C) 2018 The OpenNMS Group, Inc.
 * OpenNMS(R) is Copyright (C) 1999-2018 The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is a registered trademark of The OpenNMS Group, Inc.
 *
 * OpenNMS(R) is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License as published
 * by the Free Software Foundation, either version 3 of the License,
 * or (at your option) any later version.
 *
 * OpenNMS(R) is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with OpenNMS(R).  If not, see:
 *      http://www.gnu.org/licenses/
 *
 * For more information contact:
 *     OpenNMS(R) Licensing <license@opennms.org>
 *     http://www.opennms.org/
 *     http://www.opennms.com/
 *******************************************************************************/

package org.opennms.integrations.ca;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.stubFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.hamcrest.MatcherAssert.assertThat;
import static org.hamcrest.Matchers.equalTo;

import org.junit.Rule;
import org.junit.Test;

import com.github.tomakehurst.wiremock.junit.WireMockRule;

public class OpennmsRestClientIT {

    @Rule
    public WireMockRule wireMockRule = new WireMockRule();

    @Test
    public void canGetServerInfo() throws Exception {
        stubFor(get(urlEqualTo("/opennms/rest/info"))
                .willReturn(aResponse()
                        .withBody("{\n" +
                                "\t\"version\": \"22.0.0\"\n" +
                                "}")));
        OpennmsRestClient client = new OpennmsRestClient(String.format("http://localhost:%d/opennms", wireMockRule.port()),
                "admin", "admin");
       assertThat(client.getServerVersion(), equalTo("22.0.0"));
    }

    @Test
    public void canClearAlarm() throws Exception {
        stubFor(put(urlEqualTo("/opennms/api/v2/alarms/2924"))
                .willReturn(aResponse()
                        .withBody("OK!")));
        OpennmsRestClient client = new OpennmsRestClient(String.format("http://localhost:%d/opennms", wireMockRule.port()),
                "admin", "admin");
        client.clearAlarm(2924);
        // No exception should be thrown
    }
}
