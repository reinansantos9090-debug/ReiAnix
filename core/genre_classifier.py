"""Classificação local para organizar a biblioteca sem consultar catálogos externos."""

import re


class GenreClassifier:
    """Pequeno assistente de classificação baseado no nome das pastas e episódios.

    As regras são executadas no aparelho e podem ser expandidas pelo usuário sem
    enviar os títulos da biblioteca para nenhum serviço.
    """

    RULES = {
        "Ação": ("attack", "naruto", "dragon", "hero", "slayer", "battle", "fate", "bleach", "jujutsu"),
        "Aventura": ("one piece", "adventure", "quest", "journey", "hunter"),
        "Fantasia": ("magic", "fantasy", "dungeon", "isekai", "demon", "maou", "reincarn"),
        "Romance": ("love", "koi", "romance", "girl", "kanojo", "horimiya"),
        "Comédia": ("comedy", "comedia", "nichijou", "kaguya", "saiki"),
        "Terror": ("horror", "ghost", "corpse", "tokyo ghoul", "dark"),
        "Esporte": ("volley", "haikyuu", "football", "soccer", "basket", "sport"),
    }

    @classmethod
    def classify(cls, title: str) -> list[str]:
        normalized = re.sub(r"[^a-z0-9 ]", " ", title.lower())
        genres = [genre for genre, terms in cls.RULES.items() if any(term in normalized for term in terms)]
        return genres
