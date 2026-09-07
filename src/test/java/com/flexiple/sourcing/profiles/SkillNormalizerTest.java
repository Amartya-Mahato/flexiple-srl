package com.flexiple.sourcing.profiles;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class SkillNormalizerTest {

    private final SkillNormalizer normalizer = new SkillNormalizer();

    @Test
    void treatsTheThreeCommonSpellingsOfRdsAsOneSkill() {
        assertThat(normalizer.skillNamesReferToTheSameThing("RDS", "AWS RDS")).isTrue();
        assertThat(normalizer.skillNamesReferToTheSameThing("Amazon RDS", "AWS RDS")).isTrue();
        assertThat(normalizer.skillNamesReferToTheSameThing("aws  rds", "AWS RDS")).isTrue();
    }

    @Test
    void collapsesCommonAliasesAndPunctuation() {
        assertThat(normalizer.skillNamesReferToTheSameThing("Postgres", "PostgreSQL")).isTrue();
        assertThat(normalizer.skillNamesReferToTheSameThing("node", "Node.js")).isTrue();
        assertThat(normalizer.skillNamesReferToTheSameThing("k8s", "Kubernetes")).isTrue();
    }

    @Test
    void doesNotLetShortNamesSwallowUnrelatedSkills() {
        assertThat(normalizer.skillNamesReferToTheSameThing("Go", "MongoDB")).isFalse();
        assertThat(normalizer.skillNamesReferToTheSameThing("Go", "Django")).isFalse();
        assertThat(normalizer.skillNamesReferToTheSameThing("React", "Redis")).isFalse();
    }

    @Test
    void matchesSqlAcrossTheDatabaseFamilyBecauseThatIsWhatRecruitersMean() {
        assertThat(normalizer.skillNamesReferToTheSameThing("SQL", "PostgreSQL")).isTrue();
        assertThat(normalizer.skillNamesReferToTheSameThing("SQL", "MySQL")).isTrue();
    }
}
