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
import org.apache.commons.lang.StringUtils;
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
      BuiltInQualityProfile existing = profilesByLanguageAndName.computeIfAbsent(newProfile.language(), l -> new LinkedHashMap<>()).get(newProfile.name());
      profilesByLanguageAndName.get(newProfile.language()).put(newProfile.name(), new BuiltInQualityProfileImpl(newProfile, existing));
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

    private BuiltInQualityProfileImpl(NewBuiltInQualityProfileImpl newProfile, @Nullable BuiltInQualityProfile mergeInto) {
      this.name = newProfile.name;
      this.language = newProfile.language;
      this.isDefault = newProfile.isDefault || (mergeInto != null && mergeInto.isDefault());

      Map<RuleKey, BuiltInActiveRule> ruleBuilder = new HashMap<>();
      if (mergeInto != null) {
        if (!StringUtils.equals(newProfile.language, mergeInto.language()) || !StringUtils.equals(newProfile.name, mergeInto.name())) {
          throw new IllegalArgumentException(format("Bug - language and name of the repositories to be merged should be the sames: %s and %s", newProfile, mergeInto));
        }
        for (BuiltInActiveRule rule : mergeInto.rules()) {
          RuleKey ruleKey = RuleKey.of(rule.repoKey(), rule.ruleKey);
          if (ruleBuilder.containsKey(ruleKey)) {
            LOG.warn("The rule '{}' is activated several times in built-in quality profile '{}'", ruleKey, mergeInto.name());
          }
          ruleBuilder.put(ruleKey, rule);
        }
      }
      for (NewBuiltInActiveRule newActiveRule : newProfile.newActiveRules.values()) {
        ruleBuilder.put(RuleKey.of(newActiveRule.repoKey, newActiveRule.ruleKey), new BuiltInActiveRule(this, newActiveRule));
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
    private final BuiltInQualityProfile profile;
    private final String repoKey;
    private final String ruleKey;
    private final String overridenSeverity;
    private final Map<String, OverridenParam> overridenParams;

    private BuiltInActiveRule(BuiltInQualityProfile profile, NewBuiltInActiveRule newBuiltInActiveRule) {
      this.profile = profile;
      this.repoKey = newBuiltInActiveRule.repoKey;
      this.ruleKey = newBuiltInActiveRule.ruleKey;
      this.overridenSeverity = newBuiltInActiveRule.overridenSeverity;
      Map<String, OverridenParam> paramsBuilder = new HashMap<>();
      for (NewOverridenParam newParam : newBuiltInActiveRule.paramsByKey.values()) {
        paramsBuilder.put(newParam.key, new OverridenParam(newParam));
      }
      this.overridenParams = Collections.unmodifiableMap(paramsBuilder);
    }

    public BuiltInQualityProfile profile() {
      return profile;
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
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      BuiltInActiveRule other = (BuiltInActiveRule) o;
      return ruleKey.equals(other.ruleKey) && repoKey.equals(other.repoKey);
    }

    @Override
    public int hashCode() {
      int result = repoKey.hashCode();
      result = 31 * result + ruleKey.hashCode();
      return result;
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
      this.key = newOverridenParam.key;
      this.overridenValue = newOverridenParam.overridenValue;
    }

    public String key() {
      return key;
    }

    @Nullable
    public String overridenValue() {
      return overridenValue;
    }

    @Override
    public boolean equals(Object o) {
      if (this == o) {
        return true;
      }
      if (o == null || getClass() != o.getClass()) {
        return false;
      }
      OverridenParam that = (OverridenParam) o;
      return key.equals(that.key);
    }

    @Override
    public int hashCode() {
      return key.hashCode();
    }
  }

  /**
   * This method is executed when server is started.
   */
  void define(Context context);

}
