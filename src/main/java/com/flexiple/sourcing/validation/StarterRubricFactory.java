package com.flexiple.sourcing.validation;

import com.flexiple.sourcing.domain.ObjectiveFilters;
import com.flexiple.sourcing.domain.Rubric;
import com.flexiple.sourcing.domain.RubricCriterion;
import java.util.ArrayList;
import java.util.List;
import org.springframework.stereotype.Component;

/**
 * Builds a plain starter rubric from hand-built filters, so a manual search still has something to
 * score against the moment the recruiter decides they want an AI ranking. No model call: this is a
 * readable first draft the recruiter is expected to edit, and the UI labels it as their own.
 */
@Component
public class StarterRubricFactory {

    private static final int MAX_SKILLS_NAMED_IN_A_CRITERION = 4;

    public Rubric deriveStarterRubricFrom(ObjectiveFilters filters) {
        List<RubricCriterion> criteria = new ArrayList<>();

        if (!filters.skills().isEmpty()) {
            String namedSkills = String.join(", ", filters.skills().stream().limit(MAX_SKILLS_NAMED_IN_A_CRITERION).toList());
            criteria.add(new RubricCriterion("Depth in the required skills",
                    "Has used " + namedSkills + " substantially rather than in passing, ideally in production.", 0.4));
        }
        if (!filters.companyTypes().isEmpty() || !filters.pastCompanyTypes().isEmpty()) {
            criteria.add(new RubricCriterion("Right kind of environment",
                    "Has worked in the company backgrounds this role needs, and shows the ownership that comes with them.", 0.3));
        }
        criteria.add(new RubricCriterion("Relevant seniority and ownership",
                "Experience level fits the role, with evidence of owning work end to end rather than only contributing to it.", 0.3));

        return new Rubric("A starter rubric built from your filters. Edit it to say what good actually looks like.",
                criteria);
    }
}
