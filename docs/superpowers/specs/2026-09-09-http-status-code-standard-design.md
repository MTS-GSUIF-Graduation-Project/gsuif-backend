# HTTP Status Code Standard Update

## Goal

Make STD-28 a required baseline of semantic HTTP mappings rather than a closed whitelist, and keep the component registry consistent with that contract.

## Standards Contract

STD-28 will require:

- `200 OK` for successful non-creating operations.
- `201 Created` when a POST creates a resource.
- `400 Bad Request` for malformed requests and invalid body, parameter, or value input.
- `401 Unauthorized` for missing or invalid authentication.
- `403 Forbidden` for insufficient permission.
- `404 Not Found` for missing resources.
- `405 Method Not Allowed` for unsupported HTTP methods.
- `415 Unsupported Media Type` for unsupported request content types.
- `500 Internal Server Error` for unexpected server failures.

Other standard HTTP codes remain permitted when they are documented and semantically appropriate. Every error response must use the five-field `ApiResponse` envelope.

## Traceability Updates

`components.yaml` will be aligned as follows:

- BE-09 will include the 400, 405, and 415 request-failure mappings and reference STD-28.
- BE-11 will include 405 and 415 in the OpenAPI response coverage.
- BE-05 and BE-17 require no direct edits because they already reference STD-28.

`DECISIONS.md` and `QUESTIONS.md` will not change because this update clarifies protocol behavior without adding an architectural decision or unresolved question.

## Verification

- Parse `components.yaml` as YAML.
- Verify BE-09 references STD-28 and describes 400, 405, and 415.
- Verify BE-11 documents 405 and 415.
- Search governance files for obsolete status-code whitelists.
- Confirm the Markdown and YAML describe the same response mappings.

## Follow-up Implementation

When the exception-handling and controller work is implemented, Spring MVC exceptions for malformed input, unsupported methods, and unsupported media types must be converted to the standard envelope. Integration tests and generated OpenAPI/controller templates must enforce the same mappings.
