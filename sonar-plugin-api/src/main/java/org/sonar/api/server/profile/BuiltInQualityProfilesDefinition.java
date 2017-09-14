/*
 * SonarQube
 * Copyright (C) 2009-2017 SonarSource SA
 * mailto:info AT sonarsource DOT com
 *
 * This program is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public License
 * along with this program; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin Street, Fifth Floor, Boston, MA  02110-1301, USA.
 */
package org.sonar.api.server.profile;

import com.google.common.base.Preconditions;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.CheckForNull;
import javax.annotation.Nullable;
import javax.annotation.concurrent.Immutable;
import org.sonar.api.ExtensionPoint;
import org.sonar.api.rule.RuleKey;
import org.sonar.api.rule.Severity;
import org.sonar.api.server.ServerSide;
import org.sonar.api.utils.log.Logger;
import org.sonar.api.utils.log.Loggers;

import static com.google.common.base.Preconditions.checkArgument;
import static java.lang.String.format;
import static java.util.Collections.unmodifiableList;
import static java.util.Collections.unmodifiableMap;
import static org.apache.commons.lang.StringUtils.defaultIfEmpty;
import static org.apache.commons.lang.StringUtils.isNotBlank;

/**
 * Define built-in quality profiles which are automatically registered during SonarQube startup.
 * We no more provide any facility to load profiles from XML file or annotated classes, but it should
 * be straightforward to implement (adapt code of deprecated {@link org.sonar.api.profiles.AnnotationProfileParser} 
 * or {@link org.sonar.api.profiles.XMLProfileParser} for example).
 *
 * @since 6.6
 */
@ServerSide
@ExtensionPoint
public interface BuiltInQualityProfilesDefinition {

  /**
   * Instantiated by core but not by plugins, except for their tests.
   */
  class Context {

    private final Map<String, Map<String, BuiltInQualityProfile>> profilesByLanguageAndName = new HashMap<>();

    /**
     * New builder for {@link BuiltInQualityProfile}.
     * <br>
     * A plugin can activate rules in a built in quality profile that is defined by another plugin.
     */
    public NewBuiltInQualityProfile createBuiltInQualityProfile(String name, String language) {
      return new NewBuiltInQualityProfileImpl(this, name, language);
    }

    private void registerProfile(NewBuiltInQualityProfileImpl newProfile) {
      String language = newProfile.language();
      String name = newProfile.name();
      Preconditions.checkArgument(!profilesByLanguageAndName.computeIfAbsent(language, l -> new LinkedHashMap<>()).containsKey(name),
        "There is already a quality profile with name '%s' for language '%s'", name, language);
      profilesByLanguageAndName.get(language).put(name, new BuiltInQualityProfileImpl(newProfile));
    }

    public Map<String, Map<String, BuiltInQualityProfile>> profilesByLanguageAndName() {
      return profilesByLanguageAndName;
    }

    public BuiltInQualityProfile profile(String language, String name) {
      return profilesByLanguageAndName.computeIfAbsent(language, l -> new LinkedHashMap<>()).get(name);
    }
  }

  interface NewBuiltInQualityProfile {

    /**
     * Set whether this is the default profile for the language. The default profile is used when none is explicitly defined when analyzing a project.
     */
    NewBuiltInQualityProfile setDefault(boolean value);

    /**
     * Activate a rule with specified key.
     *
     * @throws IllegalArgumentException if rule is already activated in this profile.
     */
    NewBuiltInActiveRule activateRule(String repoKey, String ruleKey);

    Collection<NewBuiltInActiveRule> activeRules();

    String language();

    String name();

    boolean isDefault();

    void done();
  }

  class NewBuiltInQualityProfileImpl implements NewBuiltInQualityProfile {
    private final Context context;
    private final String name;
    private final String language;
    private boolean isDefault;
    private final Map<RuleKey, NewBuiltInActiveRule> newActiveRules = new HashMap<>();

    private NewBuiltInQualityProfileImpl(Context context, String name, String language) {
      this.context = context;
      this.name = name;
      this.language = language;
    }

    @Override
    public NewBuiltInQualityProfile setDefault(boolean value) {
      this.isDefault = value;
      return this;
    }

    @Override
    public NewBuiltInActiveRule activateRule(String repoKey, String ruleKey) {
      RuleKey ruleKeyObj = RuleKey.of(repoKey, ruleKey);
      checkArgument(!newActiveRules.containsKey(ruleKeyObj), "The rule '%s' is already activated", ruleKeyObj);
      NewBuiltInActiveRule newActiveRule = new NewBuiltInActiveRule(repoKey, ruleKey);
      newActiveRules.put(ruleKeyObj, newActiveRule);
      return newActiveRule;
    }

    @Override
    public String language() {
      return language;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public boolean isDefault() {
      return isDefault;
    }

    @Override
    public Collection<NewBuiltInActiveRule> activeRules() {
      return newActiveRules.values();
    }

    @Override
    public void done() {
      checkArgument(isNotBlank(name), "Built-In Quality Profile can't have a blank name");
      checkArgument(isNotBlank(language), "Built-In Quality Profile can't have a blank language");

      context.registerProfile(this);
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("NewBuiltInQualityProfile{");
      sb.append("name='").append(name).append('\'');
      sb.append(", language='").append(language).append('\'');
      sb.append(", default='").append(isDefault).append('\'');
      sb.append('}');
      return sb.toString();
    }
  }

  interface BuiltInQualityProfile {
    String name();

    String language();

    boolean isDefault();

    @CheckForNull
    BuiltInActiveRule rule(RuleKey ruleKey);

    List<BuiltInActiveRule> rules();
  }

  @Immutable
  class BuiltInQualityProfileImpl implements BuiltInQualityProfile {

    private static final Logger LOG = Loggers.get(BuiltInQualityProfilesDefinition.BuiltInQualityProfileImpl.class);
    private final String language;
    private final String name;
    private final boolean isDefault;
    private final Map<RuleKey, BuiltInActiveRule> activeRulesByKey;

    private BuiltInQualityProfileImpl(NewBuiltInQualityProfileImpl newProfile) {
      this.name = newProfile.name();
      this.language = newProfile.language();
      this.isDefault = newProfile.isDefault();

      Map<RuleKey, BuiltInActiveRule> ruleBuilder = new HashMap<>();
      for (NewBuiltInActiveRule newActiveRule : newProfile.activeRules()) {
        ruleBuilder.put(RuleKey.of(newActiveRule.repoKey, newActiveRule.ruleKey), new BuiltInActiveRule(newActiveRule));
      }
      this.activeRulesByKey = unmodifiableMap(ruleBuilder);
    }

    @Override
    public String language() {
      return language;
    }

    @Override
    public String name() {
      return name;
    }

    @Override
    public boolean isDefault() {
      return isDefault;
    }

    @Override
    @CheckForNull
    public BuiltInActiveRule rule(RuleKey ruleKey) {
      return activeRulesByKey.get(ruleKey);
    }

    @Override
    public List<BuiltInActiveRule> rules() {
      return unmodifiableList(new ArrayList<>(activeRulesByKey.values()));
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      BuiltInQualityProfileImpl that = (BuiltInQualityProfileImpl) o;
      return language.equals(that.language) && name.equals(that.name);
    }

    @Override
    public int hashCode() {
      int result = name.hashCode();
      result = 31 * result + language.hashCode();
      return result;
    }

    @Override
    public String toString() {
      StringBuilder sb = new StringBuilder("BuiltInQualityProfile{");
      sb.append("name='").append(name).append('\'');
      sb.append(", language='").append(language).append('\'');
      sb.append(", default='").append(isDefault).append('\'');
      sb.append('}');
      return sb.toString();
    }
  }

  class NewBuiltInActiveRule {
    private final String repoKey;
    private final String ruleKey;
    private String overridenSeverity = null;
    private final Map<String, NewOverridenParam> paramsByKey = new HashMap<>();

    private NewBuiltInActiveRule(String repoKey, String ruleKey) {
      this.repoKey = repoKey;
      this.ruleKey = ruleKey;
    }

    public String repoKey() {
      return this.repoKey;
    }

    public String ruleKey() {
      return this.ruleKey;
    }

    public NewBuiltInActiveRule overrideSeverity(String s) {
      checkArgument(Severity.ALL.contains(s), "Severity of rule %s is not correct: %s", RuleKey.of(repoKey, ruleKey), s);
      this.overridenSeverity = s;
      return this;
    }

    /**
     * Create a parameter with given unique key. Max length of key is 128 characters.
     */
    public NewOverridenParam overrideParam(String paramKey, @Nullable String value) {
      checkArgument(!paramsByKey.containsKey(paramKey), "The parameter '%s' was already overriden on the built in active rule %s", paramKey, this);
      NewOverridenParam param = new NewOverridenParam(paramKey).setOverridenValue(value);
      paramsByKey.put(paramKey, param);
      return param;
    }

    @CheckForNull
    public NewOverridenParam overridenParam(String paramKey) {
      return paramsByKey.get(paramKey);
    }

    public Collection<NewOverridenParam> overridenParams() {
      return paramsByKey.values();
    }

    @Override
    public String toString() {
      return format("[repository=%s, key=%s]", repoKey, ruleKey);
    }
  }

  /**
   * A rule activated on a built in quality profile.
   */
  @Immutable
  class BuiltInActiveRule {
    private final String repoKey;
    private final String ruleKey;
    private final String overridenSeverity;
    private final Map<String, OverridenParam> overridenParams;

    private BuiltInActiveRule(NewBuiltInActiveRule newBuiltInActiveRule) {
      this.repoKey = newBuiltInActiveRule.repoKey();
      this.ruleKey = newBuiltInActiveRule.ruleKey();
      this.overridenSeverity = newBuiltInActiveRule.overridenSeverity;
      Map<String, OverridenParam> paramsBuilder = new HashMap<>();
      for (NewOverridenParam newParam : newBuiltInActiveRule.overridenParams()) {
        paramsBuilder.put(newParam.key, new OverridenParam(newParam));
      }
      this.overridenParams = Collections.unmodifiableMap(paramsBuilder);
    }

    public String repoKey() {
      return repoKey;
    }

    public String ruleKey() {
      return ruleKey;
    }

    public String overridenSeverity() {
      return overridenSeverity;
    }

    @CheckForNull
    public OverridenParam overridenParam(String key) {
      return overridenParams.get(key);
    }

    public List<OverridenParam> overridenParams() {
      return unmodifiableList(new ArrayList<>(overridenParams.values()));
    }

    @Override
    public String toString() {
      return format("[repository=%s, key=%s]", repoKey, ruleKey);
    }
  }

  class NewOverridenParam {
    private final String key;
    private String overridenValue;

    private NewOverridenParam(String key) {
      this.key = key;
    }

    public String key() {
      return key;
    }

    /**
     * Empty default value will be converted to null. Max length is 4000 characters.
     */
    public NewOverridenParam setOverridenValue(@Nullable String s) {
      this.overridenValue = defaultIfEmpty(s, null);
      return this;
    }
  }

  @Immutable
  class OverridenParam {
    private final String key;
    private final String overridenValue;

    private OverridenParam(NewOverridenParam newOverridenParam) {
      this.key = newOverridenParam.key();
      this.overridenValue = newOverridenParam.overridenValue;
    }

    public String key() {
      return key;
    }

    @Nullable
    public String overridenValue() {
      return overridenValue;
    }

  }

  /**
   * This method is executed when server is started.
   */
  void define(Context context);

}
