package com.yelp.elasticsearch.plugin.scriptbuiltins;

import com.google.common.collect.ImmutableList;
import org.elasticsearch.common.component.AbstractComponent;
import org.elasticsearch.common.inject.AbstractModule;
import org.elasticsearch.common.inject.Inject;
import org.elasticsearch.common.inject.Module;
import org.elasticsearch.common.mvel2.ParserConfiguration;
import org.elasticsearch.common.settings.Settings;
import org.elasticsearch.plugins.AbstractPlugin;
import org.elasticsearch.script.ScriptEngineService;
import org.elasticsearch.script.ScriptService;
import org.elasticsearch.script.mvel.MvelScriptEngineService;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;

public class ScriptBuiltinsPlugin extends AbstractPlugin {

    public static final String IMPORTS_CONFIG_PREFIX =
            "com.yelp.elasticsearch.scriptbuiltins.imports";
    public static final String STATICS_CONFIG_PREFIX =
            "com.yelp.elasticsearch.scriptbuiltins.statics";

    private final Settings settings;

    public ScriptBuiltinsPlugin(Settings settings) {
        this.settings = settings;
    }

    @Override
    public String name() {
        return "scriptbuiltins";
    }

    @Override
    public String description() {
        return "scriptbuiltins";
    }

    @Override
    public Collection<Module> modules(Settings settings) {
        return ImmutableList.of((Module) new ScriptBuiltinsModule());
    }

    public static class ScriptBuiltinsModule extends AbstractModule {

        @Override
        protected void configure() {
            bind(ScriptBuiltinsService.class).asEagerSingleton();
        }
    }

    public static class ScriptBuiltinsService extends AbstractComponent {

        @Inject
        public ScriptBuiltinsService(Settings settings, ScriptService scriptService) {
            super(settings);

            List<ParserConfiguration> parserConfigurations =
                    getParserConfigurations(scriptService);

            for (ParserConfiguration parserConfiguration : parserConfigurations)
                registerScriptBuiltins(parserConfiguration);
        }

        private List<ParserConfiguration> getParserConfigurations(ScriptService scriptService) {
            Map<String, ScriptEngineService> scriptEngines;
            try {
                Field f = ScriptService.class.getDeclaredField("scriptEngines");
                f.setAccessible(true);
                scriptEngines = (Map<String, ScriptEngineService>) f.get(scriptService);
            } catch (Exception e) {
                logger.warn("Failed to get scriptEngines from ScriptService", e);
                return null;
            }

            ArrayList<ParserConfiguration> parserConfigurations =
                    new ArrayList<ParserConfiguration>();
            for (ScriptEngineService engineService : scriptEngines.values())
                if (engineService instanceof MvelScriptEngineService) {
                    ParserConfiguration parserConfiguration = getParserConfiguration(
                            (MvelScriptEngineService) engineService);
                    if (parserConfiguration != null)
                        parserConfigurations.add(parserConfiguration);
                }

            if (parserConfigurations.size() < 1)
                logger.warn("Failed to find MvelScriptEngineService from ScriptService scriptEngines");
            return parserConfigurations;
        }

        private ParserConfiguration getParserConfiguration(
                MvelScriptEngineService scriptEngineService) {
            try {
                Field f = MvelScriptEngineService.class.getDeclaredField("parserConfiguration");
                f.setAccessible(true);
                return (ParserConfiguration) f.get(scriptEngineService);
            } catch (Exception e) {
                logger.warn("Failed to get parserConfiguration from scriptEngineService", e);
                return null;
            }
        }

        private void registerScriptBuiltins(ParserConfiguration parserConfiguration) {
            for (String importEntry : settings.getAsArray(IMPORTS_CONFIG_PREFIX))
                parserConfiguration.addPackageImport(importEntry);

            for (String staticEntry : settings.getAsArray(STATICS_CONFIG_PREFIX))
                try {
                    Class klass = settings.getClassLoader().loadClass(staticEntry);
                    registerClassStaticMethodsAsScriptBuiltins(klass, parserConfiguration);
                } catch (Throwable e) {
                    e.printStackTrace();
                }
        }

        private void registerClassStaticMethodsAsScriptBuiltins(
                Class klass, ParserConfiguration parserConfiguration) {
            for (Method m : klass.getMethods())
                if ((m.getModifiers() & Modifier.STATIC) > 0)
                    parserConfiguration.addImport(m.getName(), m);
        }
    }
}
