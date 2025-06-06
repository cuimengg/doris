// Licensed to the Apache Software Foundation (ASF) under one
// or more contributor license agreements.  See the NOTICE file
// distributed with this work for additional information
// regarding copyright ownership.  The ASF licenses this file
// to you under the Apache License, Version 2.0 (the
// "License"); you may not use this file except in compliance
// with the License.  You may obtain a copy of the License at
//
//   http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing,
// software distributed under the License is distributed on an
// "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
// KIND, either express or implied.  See the License for the
// specific language governing permissions and limitations
// under the License.

package org.apache.doris.analysis;

import org.apache.doris.common.UserException;
import org.apache.doris.mysql.privilege.AccessControllerManager;
import org.apache.doris.mysql.privilege.PrivPredicate;
import org.apache.doris.nereids.trees.plans.commands.InstallPluginCommand;
import org.apache.doris.qe.ConnectContext;

import com.google.common.collect.Maps;
import mockit.Expectations;
import mockit.Mocked;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import java.util.Map;

public class InstallPluginCommandTest {

    @Mocked
    private AccessControllerManager accessManager;

    @Before
    public void setUp() {

        new Expectations() {
            {
                accessManager.checkGlobalPriv((ConnectContext) any, (PrivPredicate) any);
                minTimes = 0;
                result = true;
            }
        };
    }

    @Test
    public void testNormal() throws UserException {
        Map<String, String> properties = Maps.newHashMap();
        properties.put("md5sum", "7529db41471ec72e165f96fe9fb92742");
        InstallPluginCommand cmd = new InstallPluginCommand("http://test/test.zip", properties);
        Assert.assertEquals("7529db41471ec72e165f96fe9fb92742", cmd.getMd5sum());
        Assert.assertEquals("INSTALL PLUGIN FROM \"http://test/test.zip\"\n"
                + "PROPERTIES (\"md5sum\"  =  \"7529db41471ec72e165f96fe9fb92742\")", cmd.toString());
    }

}

