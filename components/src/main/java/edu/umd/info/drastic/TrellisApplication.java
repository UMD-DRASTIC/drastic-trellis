/*
 * Copyright (c) 2020 Aaron Coburn and individual contributors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package edu.umd.info.drastic;

import static org.trellisldp.app.AppUtils.printBanner;

import javax.annotation.PostConstruct;
import javax.enterprise.context.ApplicationScoped;
import javax.enterprise.inject.Produces;
import javax.ws.rs.ApplicationPath;
import javax.ws.rs.core.Application;

import org.trellisldp.api.MementoService;
import org.trellisldp.api.NoopMementoService;
import org.trellisldp.common.DefaultTimemapGenerator;
import org.trellisldp.common.TimemapGenerator;

/**
 * Web Application wrapper.
 */
@ApplicationPath("/")
@ApplicationScoped
public class TrellisApplication extends Application {

    @Produces
    TimemapGenerator timemapGenerator = new DefaultTimemapGenerator();
    
    @Produces
    MementoService mementoService = new NoopMementoService();
    
    @PostConstruct
    void init() {
        printBanner("Trellis Application", "org/trellisldp/app/banner.txt");
    }
    
}
