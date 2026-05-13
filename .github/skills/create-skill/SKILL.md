---
name: create-skill
user-invocable: true
description: '**WORKFLOW SKILL** — Guide the user through creating a new `SKILL.md` file for VS Code agent customization, including scope, location, validation, and completion checks.'
---

# Create Skill

## Purpose

Help the user author a workspace skill file (`SKILL.md`) that captures a repeatable VS Code agent customization workflow.

## When to Use

Use this skill when the user wants to:
- define a new reusable workflow for the repository
- create or update a skill asset for Copilot / VS Code customizations
- document the sequence, decisions, and success criteria for a multi-step task

## Workflow

1. Clarify the outcome.
   - What problem should the skill solve?
   - Is this a workspace-scoped skill or a user-specific customization?
   - Should this be a full workflow skill or a simpler prompt?

2. Choose the right location.
   - Workspace skills belong in `.github/skills/<name>/SKILL.md`
   - User-level customizations use `{{VSCODE_USER_PROMPTS_FOLDER}}/` and are not skill files

3. Define the skill metadata.
   - `name:` should match the folder name
   - `user-invocable:` should reflect whether the skill should appear as a slash command
   - `description:` should be specific, actionable, and include trigger keywords

4. Draft the content.
   - include the step-by-step process being followed
   - expose decision points and branching logic
   - capture checks for completion and quality
   - include examples or sample prompts if helpful

5. Validate the file.
   - confirm YAML frontmatter is syntactically correct
   - verify the file path is correct and the skill name matches the folder
   - ensure the description explains when the skill should be used

## Quality Checklist

- Skill file exists at `.github/skills/<name>/SKILL.md`
- `name` in frontmatter matches the folder name
- `description` is clear and triggerable
- workflow steps are explicit and sequential
- completion criteria are included
- skill is written for future reuse by the team

## Example Prompt

- "Create a `SKILL.md` that documents how to add a new workspace skill for repo-specific automation."
- "Help me write a skill file for reviewing and fixing VS Code agent customization files."

## Follow-up Questions

- "Should this skill support a specific file type or project area?"
- "Do you want the skill to include validation rules or example commands?"
- "Will this be a team-shared workspace skill or a personal customization?"
