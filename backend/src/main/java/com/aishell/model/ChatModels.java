package com.aishell.model;

import com.google.adk.models.springai.SpringAI;

/** Supplies the model that a single chat turn runs on, as chosen by the user. */
public interface ChatModels {

  SpringAI forTurn(String modelId);
}
