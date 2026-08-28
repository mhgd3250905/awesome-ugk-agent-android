package com.ugk.pi.android;

import java.util.Collections;
import java.util.List;

final class JavaNullSkillListProvider implements AndroidSkillProvider {
    @Override
    public List<AndroidSkill> skills() {
        return null;
    }
}

final class JavaNullSkillElementProvider implements AndroidSkillProvider {
    @Override
    public List<AndroidSkill> skills() {
        return Collections.<AndroidSkill>singletonList(null);
    }
}

final class JavaNullSkillSourceProvider implements AndroidSkillProvider {
    @Override
    public List<AndroidSkill> skills() {
        return Collections.emptyList();
    }

    @Override
    public AndroidSkillProviderSource getSource() {
        return null;
    }
}

final class JavaThrowingSkillSourceProvider implements AndroidSkillProvider {
    @Override
    public List<AndroidSkill> skills() {
        return Collections.emptyList();
    }

    @Override
    public AndroidSkillProviderSource getSource() {
        throw new IllegalStateException("java provider source exploded");
    }
}

final class JavaNullSkillProvidersPlugin implements AgentCapabilityPlugin {
    @Override
    public String getId() {
        return "java-null-skill-providers";
    }

    @Override
    public List<AgentTool> tools() {
        return Collections.emptyList();
    }

    @Override
    public List<AndroidSkill> skills() {
        return Collections.emptyList();
    }

    @Override
    public List<AndroidSkillProvider> skillProviders() {
        return null;
    }
}

final class JavaNullSkillProviderElementPlugin implements AgentCapabilityPlugin {
    @Override
    public String getId() {
        return "java-null-skill-provider-element";
    }

    @Override
    public List<AgentTool> tools() {
        return Collections.emptyList();
    }

    @Override
    public List<AndroidSkill> skills() {
        return Collections.emptyList();
    }

    @Override
    public List<AndroidSkillProvider> skillProviders() {
        return Collections.<AndroidSkillProvider>singletonList(null);
    }
}

final class JavaNullDeclaredSkillsPlugin implements AgentCapabilityPlugin {
    @Override
    public String getId() {
        return "java-null-declared-skills";
    }

    @Override
    public List<AgentTool> tools() {
        return Collections.emptyList();
    }

    @Override
    public List<AndroidSkill> skills() {
        return null;
    }
}

final class JavaNullDeclaredSkillElementPlugin implements AgentCapabilityPlugin {
    @Override
    public String getId() {
        return "java-null-declared-skill-element";
    }

    @Override
    public List<AgentTool> tools() {
        return Collections.emptyList();
    }

    @Override
    public List<AndroidSkill> skills() {
        return Collections.<AndroidSkill>singletonList(null);
    }
}

final class JavaThrowingSkillProvidersPlugin implements AgentCapabilityPlugin {
    @Override
    public String getId() {
        return "java-throwing-skill-providers";
    }

    @Override
    public List<AgentTool> tools() {
        return Collections.emptyList();
    }

    @Override
    public List<AndroidSkill> skills() {
        return Collections.emptyList();
    }

    @Override
    public List<AndroidSkillProvider> skillProviders() {
        throw new IllegalStateException("java skillProviders exploded");
    }
}

final class JavaThrowingDeclaredSkillsPlugin implements AgentCapabilityPlugin {
    @Override
    public String getId() {
        return "java-throwing-declared-skills";
    }

    @Override
    public List<AgentTool> tools() {
        return Collections.emptyList();
    }

    @Override
    public List<AndroidSkill> skills() {
        throw new IllegalStateException("java plugin.skills exploded");
    }
}
